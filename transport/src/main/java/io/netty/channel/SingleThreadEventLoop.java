/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;

import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoop}s that execute all its submitted tasks in a single thread.
 *
 * SingleThreadEventLoop 实现 EventLoop 接口，继承 SingleThreadEventExecutor 抽象类，基于单线程的 EventLoop 抽象类，主要增加了 Channel 注册到 EventLoop 上
 *
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {

    /**
     * 默认任务队列最大数量
     */
    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));

    /**
     * 尾部任务队列，执行在 {@link #taskQueue} 之后，新增了一条 tailTasks 队列，执行的顺序在 taskQueue 之后
     *
     * Commits
     *  * [Ability to run a task at the end of an eventloop iteration.](https://github.com/netty/netty/pull/5513)
     *
     * Issues
     *  * [Auto-flush for channels. (`ChannelHandler` implementation)](https://github.com/netty/netty/pull/5716)
     *  * [Consider removing executeAfterEventLoopIteration](https://github.com/netty/netty/issues/7833)
     *
     * 老艿艿：未来会移除该队列，前提是实现了 Channel 的 auto flush 功能。按照最后一个 issue 的讨论
     */
    private final Queue<Runnable> tailTasks;

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    /**
     * 获得所属 EventLoopGroup，覆盖父类方法，将返回值转换成 EventLoopGroup 类
     */
    @Override
    public EventLoopGroup parent() {
        return (EventLoopGroup) super.parent();
    }

    /**
     * 获得自己，覆盖父类方法，将返回值转换成 EventLoop 类
     */
    @Override
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    /**
     * 注册 Channel 到 EventLoop 上
     */
    @Override
    public ChannelFuture register(Channel channel) {
        // EventLoop 注册，用 Channel 和 EventLoop 创建一个 DefaultChannelPromise 对象。通过这个 DefaultChannelPromise 对象，我们就能实现对异步注册过程的监听
        // 调用 #register(final ChannelPromise promise) 方法，注册 Channel 到 EventLoop 上
        // 底层调用 AbstractUnsafe#register(EventLoop eventLoop, final ChannelPromise promise) 方法，注册 Channel 到 EventLoop 上
        return register(new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        // 注册 Channel 到 EventLoop 上
        promise.channel().unsafe().register(this, promise);
        // 返回 ChannelPromise 对象
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (promise == null) {
            throw new NullPointerException("promise");
        }

        channel.unsafe().register(this, promise);
        return promise;
    }

    /**
     * Adds a task to be run once at the end of next (or current) {@code eventloop} iteration.
     *
     * 执行一个任务，向 tailTasks 中添加收尾任务
     *
     * @param task to be added.
     */
    @UnstableApi
    public final void executeAfterEventLoopIteration(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        // 关闭时，拒绝任务
        if (isShutdown()) {
            reject();
        }

        // 添加到任务队列
        if (!tailTasks.offer(task)) {
            // 添加失败，则拒绝任务
            reject(task);
        }
        // 唤醒线程，唤醒 selector 的阻塞 select
        if (wakesUpForTask(task)) {
            wakeup(inEventLoop());
        }
    }

    /**
     * Removes a task that was added previously via {@link #executeAfterEventLoopIteration(Runnable)}.
     *
     * 移除指定任务
     *
     * @param task to be removed.
     *
     * @return {@code true} if the task was removed as a result of this call.
     */
    @UnstableApi
    final boolean removeAfterEventLoopIterationTask(Runnable task) {
        return tailTasks.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    /**
     * 判断该任务是否需要唤醒线程，当任务类型为 NonWakeupRunnable，则不进行唤醒线程
     */
    @Override
    protected boolean wakesUpForTask(Runnable task) {
        return !(task instanceof NonWakeupRunnable);
    }

    /**
     * 在运行完所有任务后，执行 tailTasks 队列中的任务
     */
    @Override
    protected void afterRunningAllTasks() {
        // 调用 #runAllTasksFrom(queue) 方法，执行 tailTasks 队列中的所有任务
        runAllTasksFrom(tailTasks);
    }

    /**
     * 队列中是否有任务，基于两个队列 tailTasks、taskQueue 来判断是否还有任务
     */
    @Override
    protected boolean hasTasks() {
        return super.hasTasks() || !tailTasks.isEmpty();
    }

    /**
     * 获得队列中的任务数，计算两个队列 tailTasks、taskQueue 的任务之和
     */
    @Override
    public int pendingTasks() {
        return super.pendingTasks() + tailTasks.size();
    }

    /**
     * Marker interface for {@link Runnable} that will not trigger an {@link #wakeup(boolean)} in all cases.
     *
     * NonWakeupRunnable 实现 Runnable 接口，用于标记不唤醒线程的任务
     */
    interface NonWakeupRunnable extends Runnable { }
}
