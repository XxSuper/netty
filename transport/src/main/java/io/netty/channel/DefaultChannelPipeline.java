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

import io.netty.channel.Channel.Unsafe;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The default {@link ChannelPipeline} implementation.  It is usually created
 * by a {@link Channel} implementation when the {@link Channel} is created.
 *
 * io.netty.channel.DefaultChannelPipeline，实现 ChannelPipeline 接口，默认 ChannelPipeline 实现类
 * 默认情况下，pipeline 有 head 和 tail 节点，形成默认的 ChannelHandler 链。而我们可以在它们之间，加入自定义的 ChannelHandler 节点
 */
public class DefaultChannelPipeline implements ChannelPipeline {

    static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultChannelPipeline.class);

    // HEAD_NAME 和 TAIL_NAME 静态属性，通过调用 #generateName0(Class<?> handlerType) 方法，生成对应的名字，即 HEAD_NAME = "HeadContext#0"，TAIL_NAME= "TailContext#0"
    private static final String HEAD_NAME = generateName0(HeadContext.class);
    private static final String TAIL_NAME = generateName0(TailContext.class);

    // nameCaches 静态属性，名字 (AbstractChannelHandlerContext.name) 缓存，基于 ThreadLocal，用于生成在线程中唯一的名字
    private static final FastThreadLocal<Map<Class<?>, String>> nameCaches =
            new FastThreadLocal<Map<Class<?>, String>>() {
        @Override
        protected Map<Class<?>, String> initialValue() {
            return new WeakHashMap<Class<?>, String>();
        }
    };

    // ESTIMATOR 静态属性，estimatorHandle 属性的原子更新器
    private static final AtomicReferenceFieldUpdater<DefaultChannelPipeline, MessageSizeEstimator.Handle> ESTIMATOR =
            AtomicReferenceFieldUpdater.newUpdater(
                    DefaultChannelPipeline.class, MessageSizeEstimator.Handle.class, "estimatorHandle");
    /**
     * Head 节点
     */
    final AbstractChannelHandlerContext head;
    /**
     * Tail 节点
     */
    final AbstractChannelHandlerContext tail;
    /**
     * 所属 Channel 对象
     */
    private final Channel channel;
    /**
     * 成功的 Promise 对象
     */
    private final ChannelFuture succeededFuture;
    /**
     * 不进行通知的 Promise 对象
     *
     * 用于一些方法执行，需要传入 Promise 类型的方法参数，但是不需要进行通知，就传入该值
     *
     * @see io.netty.channel.AbstractChannel.AbstractUnsafe#safeSetSuccess(ChannelPromise)
     */
    private final VoidChannelPromise voidPromise;

    private final boolean touch = ResourceLeakDetector.isEnabled();
    /**
     * 子执行器集合。
     *
     * 默认情况下，ChannelHandler 使用 Channel 所在的 EventLoop 作为执行器。
     * 但是如果有需要，也可以自定义执行器。详细解析，见 {@link #childExecutor(EventExecutorGroup)} 。
     * 实际情况下，基本不会用到。
     */
    private Map<EventExecutorGroup, EventExecutor> childExecutors;
    private volatile MessageSizeEstimator.Handle estimatorHandle;
    /**
     * 是否首次注册
     */
    private boolean firstRegistration = true;

    /**
     * This is the head of a linked list that is processed by {@link #callHandlerAddedForAllHandlers()} and so process
     * all the pending {@link #callHandlerAdded0(AbstractChannelHandlerContext)}.
     *
     * We only keep the head because it is expected that the list is used infrequently and its size is small.
     * Thus full iterations to do insertions is assumed to be a good compromised to saving memory and tail management
     * complexity.
     *
     * 准备添加 ChannelHandler 的回调
     */
    private PendingHandlerCallback pendingHandlerCallbackHead;

    /**
     * Set to {@code true} once the {@link AbstractChannel} is registered.Once set to {@code true} the value will never
     * change.
     *
     * Channel 是否已注册
     */
    private boolean registered;

    protected DefaultChannelPipeline(Channel channel) {
        this.channel = ObjectUtil.checkNotNull(channel, "channel");
        // succeededFuture 的创建
        succeededFuture = new SucceededChannelFuture(channel, null);
        // voidPromise 的创建
        voidPromise =  new VoidChannelPromise(channel, true);
        // 创建 Tail 节点
        tail = new TailContext(this);
        // 创建 Head 节点
        head = new HeadContext(this);
        // 相互指向，head 节点向下指向 tail 节点，tail 节点向上指向 head 节点，从而形成相互的指向
        head.next = tail;
        tail.prev = head;
    }

    final MessageSizeEstimator.Handle estimatorHandle() {
        MessageSizeEstimator.Handle handle = estimatorHandle;
        if (handle == null) {
            handle = channel.config().getMessageSizeEstimator().newHandle();
            if (!ESTIMATOR.compareAndSet(this, null, handle)) {
                handle = estimatorHandle;
            }
        }
        return handle;
    }

    final Object touch(Object msg, AbstractChannelHandlerContext next) {
        return touch ? ReferenceCountUtil.touch(msg, next) : msg;
    }

    /**
     * 创建 DefaultChannelHandlerContext 节点，而这个节点，内嵌传入的 ChannelHandler 参数
     */
    private AbstractChannelHandlerContext newContext(EventExecutorGroup group, String name, ChannelHandler handler) {
        return new DefaultChannelHandlerContext(this, childExecutor(group), name, handler);
    }

    /**
     * 创建子执行器
     */
    private EventExecutor childExecutor(EventExecutorGroup group) {
        // 当不传入 EventExecutorGroup 时，不创建子执行器.即，使用 Channel 所注册的 EventLoop 作为执行器
        if (group == null) {
            return null;
        }
        // 根据配置项 SINGLE_EVENTEXECUTOR_PER_GROUP，每个 Channel 从 EventExecutorGroup 获得不同 EventExecutor 执行器
        Boolean pinEventExecutor = channel.config().getOption(ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP);
        if (pinEventExecutor != null && !pinEventExecutor) {
            return group.next();
        }
        // 通过 childExecutors 缓存实现，每个 Channel 从 EventExecutorGroup 获得相同 EventExecutor 执行器
        Map<EventExecutorGroup, EventExecutor> childExecutors = this.childExecutors;
        if (childExecutors == null) {
            // Use size of 4 as most people only use one extra EventExecutor.
            childExecutors = this.childExecutors = new IdentityHashMap<EventExecutorGroup, EventExecutor>(4);
        }
        // Pin one of the child executors once and remember it so that the same child executor
        // is used to fire events for the same channel.
        // 缓存不存在，从 EventExecutorGroup 获得 EventExecutor 执行器
        EventExecutor childExecutor = childExecutors.get(group);
        if (childExecutor == null) {
            childExecutor = group.next();
            childExecutors.put(group, childExecutor);// 进行缓存
        }
        return childExecutor;
    }
    @Override
    public final Channel channel() {
        return channel;
    }

    @Override
    public final ChannelPipeline addFirst(String name, ChannelHandler handler) {
        return addFirst(null, name, handler);
    }

    @Override
    public final ChannelPipeline addFirst(EventExecutorGroup group, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
            checkMultiplicity(handler);
            name = filterName(name, handler);

            newCtx = newContext(group, name, handler);

            addFirst0(newCtx);

            // If the registered is false it means that the channel was not registered on an eventLoop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
                newCtx.setAddPending();
                callHandlerCallbackLater(newCtx, true);
                return this;
            }

            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                callHandlerAddedInEventLoop(newCtx, executor);
                return this;
            }
        }
        callHandlerAdded0(newCtx);
        return this;
    }

    private void addFirst0(AbstractChannelHandlerContext newCtx) {
        AbstractChannelHandlerContext nextCtx = head.next;
        newCtx.prev = head;
        newCtx.next = nextCtx;
        head.next = newCtx;
        nextCtx.prev = newCtx;
    }

    @Override
    public final ChannelPipeline addLast(String name, ChannelHandler handler) {
        return addLast(null, name, handler);
    }

    @Override
    public final ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        synchronized (this) {// 同步，为了防止多线程并发操作 pipeline 底层的双向链表
            // 检查是否有重复添加的 handler
            checkMultiplicity(handler);
            // 创建节点名
            // 创建节点
            newCtx = newContext(group, filterName(name, handler), handler);
            // 添加到最后一个节点
            addLast0(newCtx);

            // <1> pipeline 暂未注册，添加回调。再注册完成后，执行回调。详细解析，见 {@link #invokeHandlerAddedIfNeeded} 方法。
            // If the registered is false it means that the channel was not registered on an eventLoop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
                // Channel 并未注册。这种情况，发生于 ServerBootstrap 启动的过程中。
                // 在 ServerBootstrap#init(Channel channel) 方法中，会添加 ChannelInitializer 对象到 pipeline 中，恰好此时 Channel 并未注册
                // 设置 AbstractChannelHandlerContext 准备添加中
                newCtx.setAddPending();
                // 添加 PendingHandlerAddedTask 回调，在 Channel 注册完成后，执行该回调。
                callHandlerCallbackLater(newCtx, true);
                return this;
            }
            // 不在 EventLoop 的线程中，提交 EventLoop 中，执行回调用户方法
            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                callHandlerAddedInEventLoop(newCtx, executor);
                return this;
            }
        }
        // 在 EventLoop 的线程中，已经确认在 EventLoop 的线程中，所以不需要在 synchronized 括号中
        // 执行回调 ChannelHandler 添加完成(added) 事件
        callHandlerAdded0(newCtx);
        return this;
    }

    /**
     * 添加到最后一个节点。注意，实际上，是添加到 tail 节点之前
     */
    private void addLast0(AbstractChannelHandlerContext newCtx) {
        // 获得 tail 节点的前一个节点
        AbstractChannelHandlerContext prev = tail.prev;
        // 新节点，指向 prev 和 tail 节点
        newCtx.prev = prev;
        newCtx.next = tail;
        // 在 prev 和 tail，指向新节点
        prev.next = newCtx;
        tail.prev = newCtx;
    }

    @Override
    public final ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler) {
        return addBefore(null, baseName, name, handler);
    }

    @Override
    public final ChannelPipeline addBefore(
            EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        final AbstractChannelHandlerContext ctx;
        synchronized (this) {
            checkMultiplicity(handler);
            name = filterName(name, handler);
            ctx = getContextOrDie(baseName);

            newCtx = newContext(group, name, handler);

            addBefore0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventLoop yet.
            // In this case we add the context to the pipeline and add a task that will call
            // ChannelHandler.handlerAdded(...) once the channel is registered.
            if (!registered) {
                newCtx.setAddPending();
                callHandlerCallbackLater(newCtx, true);
                return this;
            }

            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                callHandlerAddedInEventLoop(newCtx, executor);
                return this;
            }
        }
        callHandlerAdded0(newCtx);
        return this;
    }

    private static void addBefore0(AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        newCtx.prev = ctx.prev;
        newCtx.next = ctx;
        ctx.prev.next = newCtx;
        ctx.prev = newCtx;
    }

    /**
     * 获得 ChannelHandler 的名字
     */
    private String filterName(String name, ChannelHandler handler) {
        if (name == null) {
            // 若未传入默认的名字 name，则调用 #generateName(ChannelHandler) 方法，根据 ChannelHandler 生成一个唯一的名字
            return generateName(handler);
        }
        // 若已传入默认的名字 name，则调用 #checkDuplicateName(String name) 方法，校验名字的唯一性
        checkDuplicateName(name);
        return name;
    }

    @Override
    public final ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler) {
        return addAfter(null, baseName, name, handler);
    }

    @Override
    public final ChannelPipeline addAfter(
            EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        final AbstractChannelHandlerContext newCtx;
        final AbstractChannelHandlerContext ctx;

        synchronized (this) {
            checkMultiplicity(handler);
            name = filterName(name, handler);
            ctx = getContextOrDie(baseName);

            newCtx = newContext(group, name, handler);

            addAfter0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventLoop yet.
            // In this case we remove the context from the pipeline and add a task that will call
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            if (!registered) {
                newCtx.setAddPending();
                callHandlerCallbackLater(newCtx, true);
                return this;
            }
            EventExecutor executor = newCtx.executor();
            if (!executor.inEventLoop()) {
                callHandlerAddedInEventLoop(newCtx, executor);
                return this;
            }
        }
        callHandlerAdded0(newCtx);
        return this;
    }

    private static void addAfter0(AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        newCtx.prev = ctx;
        newCtx.next = ctx.next;
        ctx.next.prev = newCtx;
        ctx.next = newCtx;
    }

    public final ChannelPipeline addFirst(ChannelHandler handler) {
        return addFirst(null, handler);
    }

    @Override
    public final ChannelPipeline addFirst(ChannelHandler... handlers) {
        return addFirst(null, handlers);
    }

    @Override
    public final ChannelPipeline addFirst(EventExecutorGroup executor, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }
        if (handlers.length == 0 || handlers[0] == null) {
            return this;
        }

        int size;
        for (size = 1; size < handlers.length; size ++) {
            if (handlers[size] == null) {
                break;
            }
        }

        for (int i = size - 1; i >= 0; i --) {
            ChannelHandler h = handlers[i];
            addFirst(executor, null, h);
        }

        return this;
    }

    public final ChannelPipeline addLast(ChannelHandler handler) {
        return addLast(null, handler);
    }

    /**
     * 添加任意数量的 ChannelHandler 对象
     */
    @Override
    public final ChannelPipeline addLast(ChannelHandler... handlers) {
        return addLast(null, handlers);
    }

    @Override
    public final ChannelPipeline addLast(EventExecutorGroup executor, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }

        for (ChannelHandler h: handlers) {
            if (h == null) {
                break;
            }
            // 调用 #addLast(EventExecutorGroup group, String name, ChannelHandler handler) 方法，添加一个 ChannelHandler 对象到 pipeline 中
            addLast(executor, null, h);
        }

        return this;
    }

    /**
     * 根据 ChannelHandler 生成一个唯一名字
     */
    private String generateName(ChannelHandler handler) {
        // 从缓存中查询，是否已经生成默认名字
        Map<Class<?>, String> cache = nameCaches.get();
        Class<?> handlerType = handler.getClass();
        String name = cache.get(handlerType);
        // 若未生成过，进行生成，并重新放入缓存
        if (name == null) {
            name = generateName0(handlerType);
            cache.put(handlerType, name);
        }

        // 判断是否存在相同名字的节点
        // It's not very likely for a user to put more than one handler of the same type, but make sure to avoid
        // any name conflicts.  Note that we don't cache the names generated here.
        if (context0(name) != null) {
            // 若存在，则使用基础名字 + 编号，循环生成，直到一个名字是唯一的
            String baseName = name.substring(0, name.length() - 1); // Strip the trailing '0'.
            for (int i = 1;; i ++) {
                String newName = baseName + i;
                // 判断是否存在相同名字的节点
                if (context0(newName) == null) {
                    name = newName;
                    break;
                }
            }
        }
        return name;
    }

    private static String generateName0(Class<?> handlerType) {
        // class简称加#0
        return StringUtil.simpleClassName(handlerType) + "#0";
    }

    /**
     * 从 pipeline 移除指定的 ChannelHandler 对象
     */
    @Override
    public final ChannelPipeline remove(ChannelHandler handler) {
        // 调用 #getContextOrDie(ChannelHandler handler) 方法，获得对应的 AbstractChannelHandlerContext 节点
        // 调用 #remove(AbstractChannelHandlerContext ctx) 方法，移除指定 AbstractChannelHandlerContext 节点。
        remove(getContextOrDie(handler));
        return this;
    }

    @Override
    public final ChannelHandler remove(String name) {
        return remove(getContextOrDie(name)).handler();
    }

    @SuppressWarnings("unchecked")
    @Override
    public final <T extends ChannelHandler> T remove(Class<T> handlerType) {
        return (T) remove(getContextOrDie(handlerType)).handler();
    }

    public final <T extends ChannelHandler> T removeIfExists(String name) {
        return removeIfExists(context(name));
    }

    public final <T extends ChannelHandler> T removeIfExists(Class<T> handlerType) {
        return removeIfExists(context(handlerType));
    }

    public final <T extends ChannelHandler> T removeIfExists(ChannelHandler handler) {
        return removeIfExists(context(handler));
    }

    @SuppressWarnings("unchecked")
    private <T extends ChannelHandler> T removeIfExists(ChannelHandlerContext ctx) {
        if (ctx == null) {
            return null;
        }
        return (T) remove((AbstractChannelHandlerContext) ctx).handler();
    }

    /**
     * 移除指定 AbstractChannelHandlerContext 节点
     */
    private AbstractChannelHandlerContext remove(final AbstractChannelHandlerContext ctx) {
        assert ctx != head && ctx != tail;
        // 同步，为了防止多线程并发操作 pipeline 底层的双向链表
        synchronized (this) {
            // 从 pipeline 移除指定的 AbstractChannelHandlerContext 节点
            remove0(ctx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we remove the context from the pipeline and add a task that will call
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            // pipeline 暂未注册，添加回调。在注册完成后，执行回调。详细解析，见 {@link #callHandlerCallbackLater} 方法。
            if (!registered) {
                // Channel 并未注册，调用 #callHandlerCallbackLater(AbstractChannelHandlerContext, added) 方法，添加 PendingHandlerRemovedTask 回调。在 Channel 注册完成后，执行该回调
                callHandlerCallbackLater(ctx, false);
                return ctx;
            }

            // 不在 EventLoop 的线程中，提交 EventLoop 中，执行回调用户方法
            EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop()) {
                // 提交 EventLoop 中，执行回调 ChannelHandler 移除完成 (removed) 事件
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // 回调 ChannelHandler removed 事件
                        callHandlerRemoved0(ctx);
                    }
                });
                return ctx;
            }
        }
        // 在 EventLoop 的线程中。也因为此，已经确认在 EventLoop 的线程中，所以不需要在 synchronized 中。
        // 执行回调 ChannelHandler 移除完成 (removed) 事件
        callHandlerRemoved0(ctx);
        return ctx;
    }

    /**
     * 从 pipeline 移除指定的 AbstractChannelHandlerContext 节点
     */
    private static void remove0(AbstractChannelHandlerContext ctx) {
        // 获得移除节点的前后节点
        AbstractChannelHandlerContext prev = ctx.prev;
        AbstractChannelHandlerContext next = ctx.next;
        // 前后节点互相指向
        prev.next = next;
        next.prev = prev;
    }

    @Override
    public final ChannelHandler removeFirst() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(head.next).handler();
    }

    @Override
    public final ChannelHandler removeLast() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(tail.prev).handler();
    }

    @Override
    public final ChannelPipeline replace(ChannelHandler oldHandler, String newName, ChannelHandler newHandler) {
        replace(getContextOrDie(oldHandler), newName, newHandler);
        return this;
    }

    @Override
    public final ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler) {
        return replace(getContextOrDie(oldName), newName, newHandler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T extends ChannelHandler> T replace(
            Class<T> oldHandlerType, String newName, ChannelHandler newHandler) {
        return (T) replace(getContextOrDie(oldHandlerType), newName, newHandler);
    }

    private ChannelHandler replace(
            final AbstractChannelHandlerContext ctx, String newName, ChannelHandler newHandler) {
        assert ctx != head && ctx != tail;

        final AbstractChannelHandlerContext newCtx;
        synchronized (this) {
            checkMultiplicity(newHandler);
            if (newName == null) {
                newName = generateName(newHandler);
            } else {
                boolean sameName = ctx.name().equals(newName);
                if (!sameName) {
                    checkDuplicateName(newName);
                }
            }

            newCtx = newContext(ctx.executor, newName, newHandler);

            replace0(ctx, newCtx);

            // If the registered is false it means that the channel was not registered on an eventloop yet.
            // In this case we replace the context in the pipeline
            // and add a task that will call ChannelHandler.handlerAdded(...) and
            // ChannelHandler.handlerRemoved(...) once the channel is registered.
            if (!registered) {
                callHandlerCallbackLater(newCtx, true);
                callHandlerCallbackLater(ctx, false);
                return ctx.handler();
            }
            EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop()) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
                        // because callHandlerRemoved() will trigger channelRead() or flush() on newHandler and
                        // those event handlers must be called after handlerAdded().
                        callHandlerAdded0(newCtx);
                        callHandlerRemoved0(ctx);
                    }
                });
                return ctx.handler();
            }
        }
        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
        // because callHandlerRemoved() will trigger channelRead() or flush() on newHandler and those
        // event handlers must be called after handlerAdded().
        callHandlerAdded0(newCtx);
        callHandlerRemoved0(ctx);
        return ctx.handler();
    }

    private static void replace0(AbstractChannelHandlerContext oldCtx, AbstractChannelHandlerContext newCtx) {
        AbstractChannelHandlerContext prev = oldCtx.prev;
        AbstractChannelHandlerContext next = oldCtx.next;
        newCtx.prev = prev;
        newCtx.next = next;

        // Finish the replacement of oldCtx with newCtx in the linked list.
        // Note that this doesn't mean events will be sent to the new handler immediately
        // because we are currently at the event handler thread and no more than one handler methods can be invoked
        // at the same time (we ensured that in replace().)
        prev.next = newCtx;
        next.prev = newCtx;

        // update the reference to the replacement so forward of buffered content will work correctly
        oldCtx.prev = newCtx;
        oldCtx.next = newCtx;
    }

    /**
     * 校验是否重复添加 ChannelHandler
     */
    private static void checkMultiplicity(ChannelHandler handler) {
        if (handler instanceof ChannelHandlerAdapter) {
            ChannelHandlerAdapter h = (ChannelHandlerAdapter) handler;
            // handler 不是共享的，未使用 @Sharable 注解，并且已经被添加了，则抛出异常
            // 在 pipeline 中，一个创建的 ChannelHandler 对象，如果不使用 Netty @Sharable 注解，则只能添加到一个 Channel 的 pipeline 中。所以，如果我们想要重用一个 ChannelHandler 对象(例如在 Spring 环境中)，则必须给这个 ChannelHandler 添加 @Sharable 注解
            // 若已经添加，并且未使用 @Sharable 注解，则抛出异常
            if (!h.isSharable() && h.added) {
                throw new ChannelPipelineException(
                        h.getClass().getName() +
                        " is not a @Sharable handler, so can't be added or removed multiple times.");
            }
            // 设置 handler 的 added属性为 true 表示已经被添加
            // 标记已经添加
            h.added = true;
        }
    }

    /**
     * 执行回调 ChannelHandler 添加完成 (added) 事件
     */
    private void callHandlerAdded0(final AbstractChannelHandlerContext ctx) {
        try {
            // 设置 AbstractChannelHandlerContext 已添加
            ctx.callHandlerAdded();
        } catch (Throwable t) {
            // 发生异常，移除该节点
            boolean removed = false;
            try {
                // 移除该节点，取消引用
                remove0(ctx);
                // 回调 ChannelHandler 移除完成 (removed) 事件
                ctx.callHandlerRemoved();
                // 标记移除成功
                removed = true;
            } catch (Throwable t2) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to remove a handler: " + ctx.name(), t2);
                }
            }
            // 触发异常的传播
            if (removed) {
                fireExceptionCaught(new ChannelPipelineException(
                        ctx.handler().getClass().getName() +
                        ".handlerAdded() has thrown an exception; removed.", t));
            } else {
                fireExceptionCaught(new ChannelPipelineException(
                        ctx.handler().getClass().getName() +
                        ".handlerAdded() has thrown an exception; also failed to remove.", t));
            }
        }
    }

    /**
     * 执行回调 ChannelHandler 移除完成 (removed) 事件
     */
    private void callHandlerRemoved0(final AbstractChannelHandlerContext ctx) {
        // Notify the complete removal.
        try {
            // 回调 ChannelHandler 移除完成 (removed) 事件
            ctx.callHandlerRemoved();
        } catch (Throwable t) {
            // 发生异常，触发异常的传播
            fireExceptionCaught(new ChannelPipelineException(
                    ctx.handler().getClass().getName() + ".handlerRemoved() has thrown an exception.", t));
        }
    }

    /**
     * 执行在 PendingHandlerCallback 中的 ChannelHandler 添加完成 (added) 事件
     * 它被两个方法所调用：1、AbstractUnsafe#register0(ChannelPromise promise) 2、HeadContext#channelRegistered(ChannelHandlerContext ctx)
     */
    final void invokeHandlerAddedIfNeeded() {
        // 必须在 EventLoop 的线程中
        assert channel.eventLoop().inEventLoop();
        // 仅有首次注册有效
        if (firstRegistration) {
            // 标记非首次注册
            firstRegistration = false;
            // We are now registered to the EventLoop. It's time to call the callbacks for the ChannelHandlers,
            // that were added before the registration was done.
            // 执行在 PendingHandlerCallback 中的 ChannelHandler 添加完成 (added) 事件
            callHandlerAddedForAllHandlers();
        }
    }

    @Override
    public final ChannelHandler first() {
        ChannelHandlerContext first = firstContext();
        if (first == null) {
            return null;
        }
        return first.handler();
    }

    @Override
    public final ChannelHandlerContext firstContext() {
        AbstractChannelHandlerContext first = head.next;
        if (first == tail) {
            return null;
        }
        return head.next;
    }

    @Override
    public final ChannelHandler last() {
        AbstractChannelHandlerContext last = tail.prev;
        if (last == head) {
            return null;
        }
        return last.handler();
    }

    @Override
    public final ChannelHandlerContext lastContext() {
        AbstractChannelHandlerContext last = tail.prev;
        if (last == head) {
            return null;
        }
        return last;
    }

    @Override
    public final ChannelHandler get(String name) {
        ChannelHandlerContext ctx = context(name);
        if (ctx == null) {
            return null;
        } else {
            return ctx.handler();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public final <T extends ChannelHandler> T get(Class<T> handlerType) {
        ChannelHandlerContext ctx = context(handlerType);
        if (ctx == null) {
            return null;
        } else {
            return (T) ctx.handler();
        }
    }

    @Override
    public final ChannelHandlerContext context(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        return context0(name);
    }

    @Override
    public final ChannelHandlerContext context(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        AbstractChannelHandlerContext ctx = head.next;

        // 循环，获得指定 ChannelHandler 对象的节点
        for (;;) {

            if (ctx == null) {
                return null;
            }

            // ChannelHandler 相等
            if (ctx.handler() == handler) {
                return ctx;
            }

            ctx = ctx.next;
        }
    }

    @Override
    public final ChannelHandlerContext context(Class<? extends ChannelHandler> handlerType) {
        if (handlerType == null) {
            throw new NullPointerException("handlerType");
        }

        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return null;
            }
            if (handlerType.isAssignableFrom(ctx.handler().getClass())) {
                return ctx;
            }
            ctx = ctx.next;
        }
    }

    @Override
    public final List<String> names() {
        List<String> list = new ArrayList<String>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return list;
            }
            list.add(ctx.name());
            ctx = ctx.next;
        }
    }

    @Override
    public final Map<String, ChannelHandler> toMap() {
        Map<String, ChannelHandler> map = new LinkedHashMap<String, ChannelHandler>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                return map;
            }
            map.put(ctx.name(), ctx.handler());
            ctx = ctx.next;
        }
    }

    @Override
    public final Iterator<Map.Entry<String, ChannelHandler>> iterator() {
        return toMap().entrySet().iterator();
    }

    /**
     * Returns the {@link String} representation of this pipeline.
     */
    @Override
    public final String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('{');
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                break;
            }

            buf.append('(')
               .append(ctx.name())
               .append(" = ")
               .append(ctx.handler().getClass().getName())
               .append(')');

            ctx = ctx.next;
            if (ctx == tail) {
                break;
            }

            buf.append(", ");
        }
        buf.append('}');
        return buf.toString();
    }

    @Override
    public final ChannelPipeline fireChannelRegistered() {
        AbstractChannelHandlerContext.invokeChannelRegistered(head);
        return this;
    }

    @Override
    public final ChannelPipeline fireChannelUnregistered() {
        AbstractChannelHandlerContext.invokeChannelUnregistered(head);
        return this;
    }

    /**
     * Removes all handlers from the pipeline one by one from tail (exclusive) to head (exclusive) to trigger
     * handlerRemoved().
     *
     * Note that we traverse up the pipeline ({@link #destroyUp(AbstractChannelHandlerContext, boolean)})
     * before traversing down ({@link #destroyDown(Thread, AbstractChannelHandlerContext, boolean)}) so that
     * the handlers are removed after all events are handled.
     *
     * See: https://github.com/netty/netty/issues/3156
     */
    private synchronized void destroy() {
        destroyUp(head.next, false);
    }

    private void destroyUp(AbstractChannelHandlerContext ctx, boolean inEventLoop) {
        final Thread currentThread = Thread.currentThread();
        final AbstractChannelHandlerContext tail = this.tail;
        for (;;) {
            if (ctx == tail) {
                destroyDown(currentThread, tail.prev, inEventLoop);
                break;
            }

            final EventExecutor executor = ctx.executor();
            if (!inEventLoop && !executor.inEventLoop(currentThread)) {
                final AbstractChannelHandlerContext finalCtx = ctx;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        destroyUp(finalCtx, true);
                    }
                });
                break;
            }

            ctx = ctx.next;
            inEventLoop = false;
        }
    }

    private void destroyDown(Thread currentThread, AbstractChannelHandlerContext ctx, boolean inEventLoop) {
        // We have reached at tail; now traverse backwards.
        final AbstractChannelHandlerContext head = this.head;
        for (;;) {
            if (ctx == head) {
                break;
            }

            final EventExecutor executor = ctx.executor();
            if (inEventLoop || executor.inEventLoop(currentThread)) {
                synchronized (this) {
                    remove0(ctx);
                }
                callHandlerRemoved0(ctx);
            } else {
                final AbstractChannelHandlerContext finalCtx = ctx;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        destroyDown(Thread.currentThread(), finalCtx, true);
                    }
                });
                break;
            }

            ctx = ctx.prev;
            inEventLoop = false;
        }
    }

    @Override
    public final ChannelPipeline fireChannelActive() {
        // 在方法内部，会调用 AbstractChannelHandlerContext#invokeChannelActive(final AbstractChannelHandlerContext next) 方法，而方法参数是 head
        AbstractChannelHandlerContext.invokeChannelActive(head);
        return this;
    }

    @Override
    public final ChannelPipeline fireChannelInactive() {
        AbstractChannelHandlerContext.invokeChannelInactive(head);
        return this;
    }

    @Override
    public final ChannelPipeline fireExceptionCaught(Throwable cause) {
        AbstractChannelHandlerContext.invokeExceptionCaught(head, cause);
        return this;
    }

    @Override
    public final ChannelPipeline fireUserEventTriggered(Object event) {
        AbstractChannelHandlerContext.invokeUserEventTriggered(head, event);
        return this;
    }

    @Override
    public final ChannelPipeline fireChannelRead(Object msg) {
        AbstractChannelHandlerContext.invokeChannelRead(head, msg);
        return this;
    }

    @Override
    public final ChannelPipeline fireChannelReadComplete() {
        AbstractChannelHandlerContext.invokeChannelReadComplete(head);
        return this;
    }

    @Override
    public final ChannelPipeline fireChannelWritabilityChanged() {
        AbstractChannelHandlerContext.invokeChannelWritabilityChanged(head);
        return this;
    }

    /**
     * 在方法内部，会调用 TailContext#bind(SocketAddress localAddress, ChannelPromise promise) 方法
     * @param localAddress
     * @return
     */
    @Override
    public final ChannelFuture bind(SocketAddress localAddress) {
        return tail.bind(localAddress);
    }

    @Override
    public final ChannelFuture connect(SocketAddress remoteAddress) {
        return tail.connect(remoteAddress);
    }

    @Override
    public final ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return tail.connect(remoteAddress, localAddress);
    }

    @Override
    public final ChannelFuture disconnect() {
        return tail.disconnect();
    }

    @Override
    public final ChannelFuture close() {
        return tail.close();
    }

    @Override
    public final ChannelFuture deregister() {
        return tail.deregister();
    }

    @Override
    public final ChannelPipeline flush() {
        tail.flush();
        return this;
    }

    @Override
    public final ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return tail.bind(localAddress, promise);
    }

    @Override
    public final ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return tail.connect(remoteAddress, promise);
    }

    @Override
    public final ChannelFuture connect(
            SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return tail.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public final ChannelFuture disconnect(ChannelPromise promise) {
        return tail.disconnect(promise);
    }

    @Override
    public final ChannelFuture close(ChannelPromise promise) {
        return tail.close(promise);
    }

    @Override
    public final ChannelFuture deregister(final ChannelPromise promise) {
        return tail.deregister(promise);
    }

    @Override
    public final ChannelPipeline read() {
        tail.read();
        return this;
    }

    @Override
    public final ChannelFuture write(Object msg) {
        return tail.write(msg);
    }

    @Override
    public final ChannelFuture write(Object msg, ChannelPromise promise) {
        return tail.write(msg, promise);
    }

    @Override
    public final ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return tail.writeAndFlush(msg, promise);
    }

    @Override
    public final ChannelFuture writeAndFlush(Object msg) {
        return tail.writeAndFlush(msg);
    }

    @Override
    public final ChannelPromise newPromise() {
        return new DefaultChannelPromise(channel);
    }

    @Override
    public final ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(channel);
    }

    @Override
    public final ChannelFuture newSucceededFuture() {
        return succeededFuture;
    }

    @Override
    public final ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel, null, cause);
    }

    @Override
    public final ChannelPromise voidPromise() {
        return voidPromise;
    }

    /**
     * 校验名字唯一
     */
    private void checkDuplicateName(String name) {
        // 通过调用 #context0(String name) 方法，获得指定名字的节点。若存在节点，意味着不唯一，抛出 IllegalArgumentException 异常
        if (context0(name) != null) {
            throw new IllegalArgumentException("Duplicate handler name: " + name);
        }
    }

    /**
     * 顺序向下遍历节点，判断是否有指定名字的节点。如果有，则返回该节点
     */
    private AbstractChannelHandlerContext context0(String name) {
        // 遍历 pipeline 链，判断是否存在相同名字的节点
        AbstractChannelHandlerContext context = head.next;
        while (context != tail) {
            if (context.name().equals(name)) {
                return context;
            }
            context = context.next;
        }
        return null;
    }

    private AbstractChannelHandlerContext getContextOrDie(String name) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(name);
        if (ctx == null) {
            throw new NoSuchElementException(name);
        } else {
            return ctx;
        }
    }

    /**
     * 获得对应的 AbstractChannelHandlerContext 节点，获得不到节点的情况下，会抛出 NoSuchElementException 异常
     */
    private AbstractChannelHandlerContext getContextOrDie(ChannelHandler handler) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handler);
        if (ctx == null) {
            throw new NoSuchElementException(handler.getClass().getName());
        } else {
            return ctx;
        }
    }

    private AbstractChannelHandlerContext getContextOrDie(Class<? extends ChannelHandler> handlerType) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handlerType);
        if (ctx == null) {
            throw new NoSuchElementException(handlerType.getName());
        } else {
            return ctx;
        }
    }

    private void callHandlerAddedForAllHandlers() {
        final PendingHandlerCallback pendingHandlerCallbackHead;
        // 获得 pendingHandlerCallbackHead 变量
        // 对 pendingHandlerCallbackHead 互斥，避免并发修改的问题
        synchronized (this) {
            assert !registered;

            // This Channel itself was registered.
            // 标记已注册
            registered = true;

            pendingHandlerCallbackHead = this.pendingHandlerCallbackHead;
            // Null out so it can be GC'ed.
            // 置空对象的 pendingHandlerCallbackHead 属性，help gc
            this.pendingHandlerCallbackHead = null;
        }

        // This must happen outside of the synchronized(...) block as otherwise handlerAdded(...) may be called while
        // holding the lock and so produce a deadlock if handlerAdded(...) will try to add another handler from outside
        // the EventLoop.
        // 顺序循环向下，调用 PendingHandlerCallback#execute() 方法，执行 PendingHandlerCallback 的回调，从而将 ChannelHandler 添加到 pipeline 中
        PendingHandlerCallback task = pendingHandlerCallbackHead;
        while (task != null) {
            task.execute();
            task = task.next;
        }
    }

    /**
     * 准备添加 PendingHandlerCallback 回调
     */
    private void callHandlerCallbackLater(AbstractChannelHandlerContext ctx, boolean added) {
        assert !registered;

        // 创建 PendingHandlerCallback 对象，根据 added 是否为 true，判断创建 PendingHandlerAddedTask 或 PendingHandlerRemovedTask 对象
        PendingHandlerCallback task = added ? new PendingHandlerAddedTask(ctx) : new PendingHandlerRemovedTask(ctx);
        // 将创建的 PendingHandlerCallback 对象，"添加"到 pendingHandlerCallbackHead 中
        PendingHandlerCallback pending = pendingHandlerCallbackHead;
        // 若原 pendingHandlerCallbackHead 不存在，则赋值给它
        if (pending == null) {
            pendingHandlerCallbackHead = task;
        } else {
            // Find the tail of the linked-list.
            // 若原 pendingHandlerCallbackHead 已存在，则最后一个回调指向新创建的回调
            while (pending.next != null) {
                pending = pending.next;
            }
            pending.next = task;
        }
    }

    private void callHandlerAddedInEventLoop(final AbstractChannelHandlerContext newCtx, EventExecutor executor) {
        // 设置 AbstractChannelHandlerContext 准备添加中
        newCtx.setAddPending();
        // 提交 EventLoop 中，执行回调 ChannelHandler added 事件
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 触发 ChannelHandler 的添加完成( added )事件
                callHandlerAdded0(newCtx);
            }
        });
    }

    /**
     * Called once a {@link Throwable} hit the end of the {@link ChannelPipeline} without been handled by the user
     * in {@link ChannelHandler#exceptionCaught(ChannelHandlerContext, Throwable)}.
     */
    protected void onUnhandledInboundException(Throwable cause) {
        try {
            logger.warn(
                    "An exceptionCaught() event was fired, and it reached at the tail of the pipeline. " +
                            "It usually means the last handler in the pipeline did not handle the exception.",
                    cause);
        } finally {
            ReferenceCountUtil.release(cause);
        }
    }

    /**
     * Called once the {@link ChannelInboundHandler#channelActive(ChannelHandlerContext)}event hit
     * the end of the {@link ChannelPipeline}.
     */
    protected void onUnhandledInboundChannelActive() {
    }

    /**
     * Called once the {@link ChannelInboundHandler#channelInactive(ChannelHandlerContext)} event hit
     * the end of the {@link ChannelPipeline}.
     */
    protected void onUnhandledInboundChannelInactive() {
    }

    /**
     * Called once a message hit the end of the {@link ChannelPipeline} without been handled by the user
     * in {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}. This method is responsible
     * to call {@link ReferenceCountUtil#release(Object)} on the given msg at some point.
     */
    protected void onUnhandledInboundMessage(Object msg) {
        try {
            logger.debug(
                    "Discarded inbound message {} that reached at the tail of the pipeline. " +
                            "Please check your pipeline configuration.", msg);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Called once the {@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext)} event hit
     * the end of the {@link ChannelPipeline}.
     */
    protected void onUnhandledInboundChannelReadComplete() {
    }

    /**
     * Called once an user event hit the end of the {@link ChannelPipeline} without been handled by the user
     * in {@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)}. This method is responsible
     * to call {@link ReferenceCountUtil#release(Object)} on the given event at some point.
     */
    protected void onUnhandledInboundUserEventTriggered(Object evt) {
        // This may not be a configuration error and so don't log anything.
        // The event may be superfluous for the current pipeline configuration.
        ReferenceCountUtil.release(evt);
    }

    /**
     * Called once the {@link ChannelInboundHandler#channelWritabilityChanged(ChannelHandlerContext)} event hit
     * the end of the {@link ChannelPipeline}.
     */
    protected void onUnhandledChannelWritabilityChanged() {
    }

    @UnstableApi
    protected void incrementPendingOutboundBytes(long size) {
        ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
        if (buffer != null) {
            buffer.incrementPendingOutboundBytes(size);
        }
    }

    @UnstableApi
    protected void decrementPendingOutboundBytes(long size) {
        ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
        if (buffer != null) {
            buffer.decrementPendingOutboundBytes(size);
        }
    }

    /**
     * pipeline 中的节点的数据结构是 ChannelHandlerContext 类。每个 ChannelHandlerContext 包含一个 ChannelHandler、它的上下节点(从而形成 ChannelHandler 链)、以及其他上下文。
     *
     * TailContext，实现 ChannelInboundHandler 接口，继承 AbstractChannelHandlerContext 抽象类，pipeline 尾节点 Context 实现类
     *
     * tail 节点的作用就是结束事件传播，并且对一些重要的事件做一些善意提醒
     */
    // A special catch-all handler that handles both bytes and messages.
    final class TailContext extends AbstractChannelHandlerContext implements ChannelInboundHandler {

        TailContext(DefaultChannelPipeline pipeline) {
            // 调用父 AbstractChannelHandlerContext 的构造方法，设置 inbound = true、outbound = false
            super(pipeline, null, TAIL_NAME, true, false);
            // 调用 #setAddComplete() 方法，设置 ChannelHandler 添加完成。此时，handlerState 会变成 ADD_COMPLETE 状态
            setAddComplete();
        }

        // 返回自己作为 Context 的 ChannelHandler。因为 TailContext，实现 ChannelInboundHandler 接口，而它们本身就是 ChannelHandler
        @Override
        public ChannelHandler handler() {
            return this;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) { }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) { }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            onUnhandledInboundChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            onUnhandledInboundChannelInactive();
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            onUnhandledChannelWritabilityChanged();
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) { }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) { }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            onUnhandledInboundUserEventTriggered(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            onUnhandledInboundException(cause);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            onUnhandledInboundMessage(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            onUnhandledInboundChannelReadComplete();
        }
    }

    /**
     * HeadContext，实现 ChannelOutboundHandler、ChannelInboundHandler 接口，继承 AbstractChannelHandlerContext 抽象类，pipeline 头节点 Context 实现类
     */
    final class HeadContext extends AbstractChannelHandlerContext
            implements ChannelOutboundHandler, ChannelInboundHandler {

        private final Unsafe unsafe;

        HeadContext(DefaultChannelPipeline pipeline) {
            // 调用父 AbstractChannelHandlerContext 的构造方法，设置 inbound = true、outbound = true
            super(pipeline, null, HEAD_NAME, true, true);
            // 使用 Channel 的 Unsafe 作为 unsafe 属性。HeadContext 实现 ChannelOutboundHandler 接口的方法，都会调用 Unsafe 对应的方法
            // 这也就是为什么设置 outbound = true 的原因
            unsafe = pipeline.channel().unsafe();
            // 调用 #setAddComplete() 方法，设置 ChannelHandler 添加完成。此时，handlerState 会变成 ADD_COMPLETE 状态
            setAddComplete();
        }

        // 返回自己作为 Context 的 ChannelHandler，因为 HeadContext，实现 ChannelOutboundHandler、ChannelInboundHandler 接口，而它们本身就是 ChannelHandler
        @Override
        public ChannelHandler handler() {
            return this;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            // NOOP
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            // NOOP
        }

        @Override
        public void bind(
                ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
            // 调用 Unsafe#bind(SocketAddress localAddress, ChannelPromise promise) 方法，进行 bind 事件的处理。也就是说 Unsafe 是 bind 的处理者
            unsafe.bind(localAddress, promise);
        }

        @Override
        public void connect(
                ChannelHandlerContext ctx,
                SocketAddress remoteAddress, SocketAddress localAddress,
                ChannelPromise promise) {
            unsafe.connect(remoteAddress, localAddress, promise);
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
            unsafe.disconnect(promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            unsafe.close(promise);
        }

        @Override
        public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
            unsafe.deregister(promise);
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
            unsafe.beginRead();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            unsafe.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            unsafe.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.fireExceptionCaught(cause);
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) {
            invokeHandlerAddedIfNeeded();
            ctx.fireChannelRegistered();
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) {
            ctx.fireChannelUnregistered();

            // Remove all handlers sequentially if channel is closed and unregistered.
            if (!channel.isOpen()) {
                destroy();
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // 传播 Channel active 事件给下一个 Inbound 节点
            ctx.fireChannelActive();
            // 执行 read 逻辑
            readIfIsAutoRead();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            ctx.fireChannelInactive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.fireChannelReadComplete();

            readIfIsAutoRead();
        }

        private void readIfIsAutoRead() {
            if (channel.config().isAutoRead()) {
                // 该方法内部，会调用 Channel#read() 方法，而后通过 pipeline 传递该 read OutBound 事件，最终调用 HeadContext#read() 方法
                channel.read();
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            ctx.fireChannelWritabilityChanged();
        }
    }

    /**
     * PendingHandlerCallback，实现 Runnable 接口，等待添加 ChannelHandler 回调抽象类，通过 ctx 和 next 字段，形成回调链。
     *
     * 为什么会有 PendingHandlerCallback 呢？
     * 因为 ChannelHandler 添加到 pipeline 中，会触发 ChannelHandler 的添加完成 (added) 事件，并且该事件需要在 Channel 所属的 EventLoop 中执行。
     * 但是 Channel 并未注册在 EventLoop 上时，需要暂时将"触发 ChannelHandler 的添加完成 (added) 事件"的逻辑，作为一个 PendingHandlerCallback 进行"缓存"。在 Channel 注册到 EventLoop 上时，进行回调执行。
     */
    private abstract static class PendingHandlerCallback implements Runnable {
        /**
         * AbstractChannelHandlerContext 节点
         */
        final AbstractChannelHandlerContext ctx;
        /**
         * 下一个回调 PendingHandlerCallback 对象
         */
        PendingHandlerCallback next;

        PendingHandlerCallback(AbstractChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
        /**
         * 抽象方法，通过实现它，执行回调逻辑，执行方法
         */
        abstract void execute();
    }

    /**
     * PendingHandlerAddedTask 实现 PendingHandlerCallback 抽象类，用于回调添加 ChannelHandler 节点
     *
     * 为什么 PendingHandlerAddedTask 可以直接提交到 EventLoop 中呢？
     * 因为 PendingHandlerAddedTask 是个 Runnable，这也就是为什么 PendingHandlerCallback 实现 Runnable 接口的原因
     */
    private final class PendingHandlerAddedTask extends PendingHandlerCallback {

        PendingHandlerAddedTask(AbstractChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        public void run() {
            callHandlerAdded0(ctx);
        }

        @Override
        void execute() {
            EventExecutor executor = ctx.executor();
            // 在 EventLoop 的线程中，回调 ChannelHandler 添加完成(added) 事件
            if (executor.inEventLoop()) {
                callHandlerAdded0(ctx);
            } else {
                // 提交 EventLoop 中，执行回调 ChannelHandler added 事件
                try {
                    executor.execute(this);
                } catch (RejectedExecutionException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(
                                "Can't invoke handlerAdded() as the EventExecutor {} rejected it, removing handler {}.",
                                executor, ctx.name(), e);
                    }
                    // 发生异常，进行移除
                    remove0(ctx);
                    // 标记 AbstractChannelHandlerContext 为已移除
                    ctx.setRemoved();
                }
            }
        }
    }

    /**
     * PendingHandlerRemovedTask 实现 PendingHandlerCallback 抽象类，用于回调移除 ChannelHandler 节点。
     * 为什么 PendingHandlerRemovedTask 可以直接提交到 EventLoop 中呢？
     * 因为 PendingHandlerRemovedTask 是个 Runnable，这也就是为什么 PendingHandlerCallback 实现 Runnable 接口的原因
     */
    private final class PendingHandlerRemovedTask extends PendingHandlerCallback {

        PendingHandlerRemovedTask(AbstractChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        public void run() {
            callHandlerRemoved0(ctx);
        }

        @Override
        void execute() {
            EventExecutor executor = ctx.executor();
            // 在 EventLoop 的线程中，回调 ChannelHandler 移除完成(removed) 事件
            if (executor.inEventLoop()) {
                callHandlerRemoved0(ctx);
            } else {
                try {
                    // 提交 EventLoop 中，执行回调 ChannelHandler 移除完成(removed) 事件
                    executor.execute(this);
                } catch (RejectedExecutionException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(
                                "Can't invoke handlerRemoved() as the EventExecutor {} rejected it," +
                                        " removing handler {}.", executor, ctx.name(), e);
                    }
                    // remove0(...) was call before so just call AbstractChannelHandlerContext.setRemoved().
                    // 标记 AbstractChannelHandlerContext 为已移除
                    ctx.setRemoved();
                }
            }
        }
    }
}
