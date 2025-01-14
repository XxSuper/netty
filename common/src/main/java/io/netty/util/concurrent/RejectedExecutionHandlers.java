/*
 * Copyright 2016 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Expose helper methods which create different {@link RejectedExecutionHandler}s.
 *
 * RejectedExecutionHandler 实现类枚举，目前有 2 种实现类
 */
public final class RejectedExecutionHandlers {
    private static final RejectedExecutionHandler REJECT = new RejectedExecutionHandler() {
        @Override
        public void rejected(Runnable task, SingleThreadEventExecutor executor) {
            throw new RejectedExecutionException();
        }
    };

    private RejectedExecutionHandlers() { }

    /**
     * Returns a {@link RejectedExecutionHandler} that will always just throw a {@link RejectedExecutionException}.
     *
     * 通过 #reject() 方法，返回 REJECT 实现类的对象。该实现在拒绝时，直接抛出 RejectedExecutionException 异常，默认情况下，使用这种实现
     */
    public static RejectedExecutionHandler reject() {
        return REJECT;
    }

    /**
     * Tries to backoff when the task can not be added due restrictions for an configured amount of time. This
     * is only done if the task was added from outside of the event loop which means
     * {@link EventExecutor#inEventLoop()} returns {@code false}.
     *
     * 通过 #backoff(final int retries, long backoffAmount, TimeUnit unit) 方法，创建带多次尝试添加到任务队列的 RejectedExecutionHandler 实现类
     */
    public static RejectedExecutionHandler backoff(final int retries, long backoffAmount, TimeUnit unit) {
        ObjectUtil.checkPositive(retries, "retries");
        final long backOffNanos = unit.toNanos(backoffAmount);
        return new RejectedExecutionHandler() {
            @Override
            public void rejected(Runnable task, SingleThreadEventExecutor executor) {
                // 非 EventLoop 线程中。如果在 EventLoop 线程中，就无法执行任务（任务再 EventLoop 线程中已经被拒接），这就导致完全无法重试了。
                if (!executor.inEventLoop()) {
                    // 循环多次尝试添加到队列中
                    for (int i = 0; i < retries; i++) {
                        // Try to wake up the executor so it will empty its task queue.
                        // 唤醒执行器，进行任务执行。这样，就可能执行掉部分任务。
                        executor.wakeup(false);

                        // 阻塞等待
                        LockSupport.parkNanos(backOffNanos);

                        // 添加任务
                        if (executor.offerTask(task)) {
                            return;
                        }
                    }
                }
                // Either we tried to add the task from within the EventLoop or we was not able to add it even with
                // backoff.
                // 多次尝试添加失败，抛出 RejectedExecutionException 异常
                throw new RejectedExecutionException();
            }
        };
    }
}
