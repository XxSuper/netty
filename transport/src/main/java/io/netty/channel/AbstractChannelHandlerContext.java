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

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.*;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.OrderedEventExecutor;
import io.netty.util.internal.*;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * io.netty.channel.AbstractChannelHandlerContext，实现 ChannelHandlerContext、ResourceLeakHint 接口，继承 DefaultAttributeMap 类，ChannelHandlerContext 抽象基类
 */
abstract class AbstractChannelHandlerContext extends DefaultAttributeMap
        implements ChannelHandlerContext, ResourceLeakHint {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannelHandlerContext.class);
    /**
     * 下一个节点
     */
    volatile AbstractChannelHandlerContext next;
    /**
     * 上一个节点
     */
    volatile AbstractChannelHandlerContext prev;

    /**
     * {@link #handlerState} 的原子更新器
     */
    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} is about to be called.
     */
    private static final int ADD_PENDING = 1; // 添加准备中
    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called.
     */
    private static final int ADD_COMPLETE = 2; // 已添加
    /**
     * {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    private static final int REMOVE_COMPLETE = 3; // 已移除
    /**
     * Neither {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}
     * nor {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    private static final int INIT = 0;// 初始化
    /**
     * 是否为 inbound 处理器
     */
    private final boolean inbound;
    /**
     * 是否为 outbound 处理器
     */
    private final boolean outbound;
    /**
     * 所属 pipeline
     */
    private final DefaultChannelPipeline pipeline;
    /**
     * 处理器名字
     */
    private final String name;
    /**
     * 是否使用有序的 EventExecutor ( {@link #executor} )，即 OrderedEventExecutor
     */
    private final boolean ordered;

    // Will be set to null if no child executor should be used, otherwise it will be set to the
    // child executor.
    /**
     * EventExecutor 对象
     */
    final EventExecutor executor;
    /**
     * 成功的 Promise 对象
     */
    private ChannelFuture succeededFuture;

    // Lazily instantiated tasks used to trigger events to a handler with different executor.
    // There is no need to make this volatile as at worse it will just create a few more instances then needed.
    private Tasks invokeTasks;
    /**
     * 处理器状态，共有 4 种状态，初始为 INIT
     */
    private volatile int handlerState = INIT;

    AbstractChannelHandlerContext(DefaultChannelPipeline pipeline, EventExecutor executor, String name,
                                  boolean inbound, boolean outbound) {
        this.name = ObjectUtil.checkNotNull(name, "name");
        this.pipeline = pipeline;
        this.executor = executor;
        this.inbound = inbound;
        this.outbound = outbound;
        // Its ordered if its driven by the EventLoop or the given Executor is an instanceof OrderedEventExecutor.
        ordered = executor == null || executor instanceof OrderedEventExecutor;
    }

    @Override
    public Channel channel() {
        return pipeline.channel();
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return channel().config().getAllocator();
    }

    @Override
    public EventExecutor executor() {
        // 如果未设置子执行器，则使用 Channel 的 EventLoop 作为执行器
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandlerContext fireChannelRegistered() {
        invokeChannelRegistered(findContextInbound());
        return this;
    }

    static void invokeChannelRegistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelRegistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRegistered();
                }
            });
        }
    }

    private void invokeChannelRegistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelRegistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
        invokeChannelUnregistered(findContextInbound());
        return this;
    }

    static void invokeChannelUnregistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelUnregistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelUnregistered();
                }
            });
        }
    }

    private void invokeChannelUnregistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelUnregistered(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelUnregistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelActive() {
        // 传播 Inbound 事件给下一个节点
        // 获得下一个 Inbound 节点
        // 调用下一个 Inbound 节点的 Channel active 方法
        invokeChannelActive(findContextInbound());
        return this;
    }

    /**
     * 传递的第一个 next 方法参数为 head (HeadContext) 节点
     *
     * @param next 下一个 Inbound 节点
     */
    static void invokeChannelActive(final AbstractChannelHandlerContext next) {
        // 获得下一个 Inbound 节点的执行器
        EventExecutor executor = next.executor();
        // 调用下一个 Inbound 节点的 Channel active 方法
        if (executor.inEventLoop()) {
            next.invokeChannelActive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelActive();
                }
            });
        }
    }

    /**
     * 实际上，此时节点的数据类型为 DefaultChannelHandlerContext 类。若它被认为是 Inbound 节点，那么他的处理器的类型会是 ChannelInboundHandler。
     * 而 io.netty.channel.ChannelInboundHandler 类似 ChannelInboundInvoker，定义了对每个 Inbound 事件的处理。
     *
     * 如果节点的 ChannelInboundHandler#channelActive(ChannelHandlerContext ctx) 方法的实现，不调用 AbstractChannelHandlerContext#fireChannelActive() 方法，
     * 就不会传播 Inbound 事件给下一个节点
     */
    private void invokeChannelActive() {
        // 判断是否是符合的 ChannelHandler
        if (invokeHandler()) {
            try {
                // 若是符合的 ChannelHandler，调用 ChannelHandler 的 Channel active 方法，处理 Channel active 事件
                // 如果节点的方法的实现不调用 fireChannelActive() 就不会传播 Inbound 事件给下一个节点
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
                // 通知 Inbound 事件的传播，发生异常
                notifyHandlerException(t);
            }
        } else {
            // 若是不符合的 ChannelHandler，则跳过该节点，传播 Inbound 事件给下一个节点
            fireChannelActive();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
        invokeChannelInactive(findContextInbound());
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelInactive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelInactive();
        }
    }

    @Override
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        invokeExceptionCaught(next, cause);
        return this;
    }

    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        ObjectUtil.checkNotNull(cause, "cause");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeExceptionCaught(cause);
        } else {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to submit an exceptionCaught() event.", t);
                    logger.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }

    private void invokeExceptionCaught(final Throwable cause) {
        if (invokeHandler()) {
            try {
                handler().exceptionCaught(this, cause);
            } catch (Throwable error) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                        "An exception {}" +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:",
                        ThrowableUtil.stackTraceToString(error), cause);
                } else if (logger.isWarnEnabled()) {
                    logger.warn(
                        "An exception '{}' [enable DEBUG level for full stacktrace] " +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:", error, cause);
                }
            }
        } else {
            fireExceptionCaught(cause);
        }
    }

    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
        invokeUserEventTriggered(findContextInbound(), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeUserEventTriggered(event);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
        invokeChannelRead(findContextInbound(), msg);
        return this;
    }

    static void invokeChannelRead(final AbstractChannelHandlerContext next, Object msg) {
        final Object m = next.pipeline.touch(ObjectUtil.checkNotNull(msg, "msg"), next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelRead(m);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRead(m);
                }
            });
        }
    }

    private void invokeChannelRead(Object msg) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelRead(msg);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
        invokeChannelReadComplete(findContextInbound());
        return this;
    }

    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelReadComplete();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelReadCompleteTask);
        }
    }

    private void invokeChannelReadComplete() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
        invokeChannelWritabilityChanged(findContextInbound());
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelWritabilityChanged();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelWritableStateChangedTask);
        }
    }

    private void invokeChannelWritabilityChanged() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelWritabilityChanged();
        }
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }

    @Override
    public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        // 判断 promise 是否为合法的 Promise 对象
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }
        // 获得下一个 Outbound 节点
        final AbstractChannelHandlerContext next = findContextOutbound();
        // 获得下一个 Outbound 节点的执行器
        EventExecutor executor = next.executor();
        // 调用下一个 Outbound 节点的 bind 方法
        if (executor.inEventLoop()) {
            // 在 EventLoop 的线程中，调用下一个节点的 AbstractChannelHandlerContext#invokeBind(SocketAddress localAddress, ChannelPromise promise) 方法，传播 bind 事件给下一个节点
            next.invokeBind(localAddress, promise);
        } else {
            // 如果不在 EventLoop 的线程中，会调用 #safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) 方法，提交到 EventLoop 的线程中执行
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeBind(localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    /**
     * 如果节点的 ChannelOutboundHandler#bind(ChannelHandlerContext ctx, SocketAddress localAddress，ChannelPromise promise) 方法的实现，
     * 不调用 AbstractChannelHandlerContext#bind(SocketAddress localAddress, ChannelPromise promise) 方法，就不会传播 Outbound 事件给下一个节点
     */
    private void invokeBind(SocketAddress localAddress, ChannelPromise promise) {
        // 判断是否是符合的 ChannelHandler
        if (invokeHandler()) {
            try {
                // 若是符合的 ChannelHandler，调用该 ChannelHandler 的 bind 方法，处理 bind 事件
                // 如果 handel 的实现不调用 ctx.bind() 则就不会传播 Outbound 事件给下一个节点
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
                // 如果发生异常，调用 #notifyOutboundHandlerException(Throwable, Promise) 方法，通知 Outbound 事件的传播，发生异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            // 若是不符合的 ChannelHandler，则跳过该节点，传播 Outbound 事件给下一个节点
            bind(localAddress, promise);
        }
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return connect(remoteAddress, null, promise);
    }

    @Override
    public ChannelFuture connect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {

        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeConnect(remoteAddress, localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeConnect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            connect(remoteAddress, localAddress, promise);
        }
    }

    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
        // 判断是否为合法的 Promise 对象
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            // Translate disconnect to close if the channel has no notion of disconnect-reconnect.
            // So far, UDP/IP is the only transport that has such behavior.
            // 判断 Channel 是否支持 disconnect 操作，只有 Java 原生 UDP DatagramSocket 支持 disconnect 操作。
            if (!channel().metadata().hasDisconnect()) {
                // 如果没有 disconnect 操作，则执行 close 事件在 pipeline 上
                // 如果不支持，则转换执行 close 事件在 pipeline 上。
                next.invokeClose(promise);
            } else {
                // 如果有 disconnect 操作，则执行 disconnect 事件在 pipeline 上
                // 如果支持，则保持执行 disconnect 事件在 pipeline 上
                next.invokeDisconnect(promise);
            }
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    if (!channel().metadata().hasDisconnect()) {
                        // 如果没有 disconnect 操作，则执行 close 事件在 pipeline 上
                        next.invokeClose(promise);
                    } else {
                        // 如果有 disconnect 操作，则执行 disconnect 事件在 pipeline 上
                        next.invokeDisconnect(promise);
                    }
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeDisconnect(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            disconnect(promise);
        }
    }

    @Override
    public ChannelFuture close(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeClose(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeClose(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeClose(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            close(promise);
        }
    }

    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDeregister(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDeregister(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeDeregister(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            deregister(promise);
        }
    }

    @Override
    public ChannelHandlerContext read() {
        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeRead();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeReadTask);
        }

        return this;
    }

    private void invokeRead() {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            read();
        }
    }

    @Override
    public ChannelFuture write(Object msg) {
        // #write(Object msg) 方法，会调用 #write(Object msg, ChannelPromise promise) 方法
        // 缺少的 promise 方法参数，通过调用 #newPromise() 方法，进行创建 Promise 对象，返回的结果就是传入的 promise 对象
        return write(msg, newPromise());
    }

    @Override
    public ChannelFuture write(final Object msg, final ChannelPromise promise) {
        write(msg, false, promise);

        return promise;
    }

    private void invokeWrite(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            write(msg, promise);
        }
    }

    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }

    /**
     * TailContext 对 TailContext#flush() 方法的实现，是从 AbstractChannelHandlerContext 抽象类继承
     */
    @Override
    public ChannelHandlerContext flush() {
        // 获得下一个 Outbound 节点
        final AbstractChannelHandlerContext next = findContextOutbound();
        EventExecutor executor = next.executor();
        // 在 EventLoop 的线程中
        if (executor.inEventLoop()) {
            // 执行 flush 事件到下一个节点，随着 flush 事件不断的向下一个节点传播，最终会到达 HeadContext 节点。
            next.invokeFlush();
        } else {
            // 不在 EventLoop 的线程中，创建 flush 任务
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            // 提交到 EventLoop 的线程中，执行该任务。从而实现，在 EventLoop 的线程中，执行 flush 事件到下一个节点。
            safeExecute(executor, tasks.invokeFlushTask, channel().voidPromise(), null);
        }

        return this;
    }

    private void invokeFlush() {
        if (invokeHandler()) {
            invokeFlush0();
        } else {
            flush();
        }
    }

    private void invokeFlush0() {
        try {
            ((ChannelOutboundHandler) handler()).flush(this);
        } catch (Throwable t) {
            notifyHandlerException(t);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        write(msg, true, promise);
        return promise;
    }

    private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            // 执行 write 事件到下一个节点
            invokeWrite0(msg, promise);
            // 执行 flush 事件到下一个节点
            invokeFlush0();
        } else {
            writeAndFlush(msg, promise);
        }
    }

    private void write(Object msg, boolean flush, ChannelPromise promise) {
        // 若消息为空，抛出异常
        ObjectUtil.checkNotNull(msg, "msg");
        try {
            // 判断是否为不合法的 Promise 对象
            if (isNotValidPromise(promise, true)) {
                // 释放消息 (数据) 相关的资源
                ReferenceCountUtil.release(msg);
                // cancelled
                return;
            }
        } catch (RuntimeException e) {
            // 发生异常，释放消息 (数据) 相关的资源。最终，会抛出该异常
            ReferenceCountUtil.release(msg);
            throw e;
        }

        // 写入消息 (数据) 到内存队列
        // 获得下一个 Outbound 节点
        AbstractChannelHandlerContext next = findContextOutbound();
        // 记录 Record 记录
        final Object m = pipeline.touch(msg, next);
        EventExecutor executor = next.executor();
        // 在 EventLoop 的线程中
        if (executor.inEventLoop()) {
            if (flush) {
                // 执行 writeAndFlush 事件到下一个节点
                next.invokeWriteAndFlush(m, promise);
            } else {
                // 执行 write 事件到下一个节点
                next.invokeWrite(m, promise);
            }
        } else {
            // 不在 EventLoop 的线程中
            final AbstractWriteTask task;
            if (flush) {
                // 创建 writeAndFlush 任务。flush 为 true 时，该方法执行的是 write + flush 的组合操作，即将数据写到内存队列后，立即刷新内存队列，又将其中的数据写入到对端。
                task = WriteAndFlushTask.newInstance(next, m, promise);
            }  else {
                // 创建 write 任务
                task = WriteTask.newInstance(next, m, promise);
            }
            // 提交到 EventLoop 的线程中，执行该任务。从而实现，在 EventLoop 的线程中，执行 writeAndFlush 或 write 事件到下一个节点
            if (!safeExecute(executor, task, promise, m)) {
                // We failed to submit the AbstractWriteTask. We need to cancel it so we decrement the pending bytes
                // and put it back in the Recycler for re-use later.
                //
                // See https://github.com/netty/netty/issues/8343.
                task.cancel();
            }
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return writeAndFlush(msg, newPromise());
    }

    /**
     * 通知 Outbound 事件的传播，发生异常
     */
    private static void notifyOutboundHandlerException(Throwable cause, ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        // 在方法内部，会调用 PromiseNotificationUtil#tryFailure(Promise<?> p, Throwable cause, InternalLogger logger) 方法，通知 bind 事件对应的 Promise 对应的监听者们
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    /**
     * 通知 Inbound 事件的传播，发生异常。
     */
    private void notifyHandlerException(Throwable cause) {
        // 如果是在 `ChannelHandler#exceptionCaught(ChannelHandlerContext ctx, Throwable cause)` 方法中，仅打印错误日志，并 return 返回，否则会形成死循环。
        if (inExceptionCaught(cause)) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "An exception was thrown by a user handler " +
                                "while handling an exceptionCaught event", cause);
            }
            return;
        }

        // 在 pipeline 中，传播 Exception Caught 事件。Exception Caught 事件在 pipeline 的起始节点，不是 head 头节点，而是发生异常的当前节点开始，
        // 对于在 pipeline 上传播的 Inbound 事件，在发生异常后，转化成 Exception Caught 事件，继续从当前节点，继续向下传播，如果 Exception Caught 事件在 pipeline 中的传播过程中，
        // 一直没有处理掉该异常的节点，最终会到达尾节点 tail，，它对 #exceptionCaught(ChannelHandlerContext ctx, Throwable cause) 方法的实现，打印告警日志，
        // 并调用 ReferenceCountUtil#release(Throwable) 方法，释放需要释放的资源。
        invokeExceptionCaught(cause);
    }

    private static boolean inExceptionCaught(Throwable cause) {
        do {
            StackTraceElement[] trace = cause.getStackTrace();
            if (trace != null) {
                // 循环 StackTraceElement。通过 StackTraceElement 的方法名来判断，是不是 ChannelHandler#exceptionCaught(ChannelHandlerContext ctx, Throwable cause) 方法
                for (StackTraceElement t : trace) {
                    if (t == null) {
                        break;
                    }
                    // 通过方法名判断
                    if ("exceptionCaught".equals(t.getMethodName())) {
                        return true;
                    }
                }
            }

            cause = cause.getCause();
        } while (cause != null);// 循环异常的 cause()，直到没有

        return false;
    }

    /**
     * 返回 DefaultChannelPromise 对象
     */
    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(channel(), executor());
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(channel(), executor());
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        ChannelFuture succeededFuture = this.succeededFuture;
        if (succeededFuture == null) {
            this.succeededFuture = succeededFuture = new SucceededChannelFuture(channel(), executor());
        }
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel(), executor(), cause);
    }

    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        if (promise == null) {
            throw new NullPointerException("promise");
        }
        // Promise 已经完成
        if (promise.isDone()) {
            // Check if the promise was cancelled and if so signal that the processing of the operation
            // should not be performed.
            //
            // See https://github.com/netty/netty/issues/2349
            if (promise.isCancelled()) {
                return true;
            }
            throw new IllegalArgumentException("promise already done: " + promise);
        }
        // Channel 不符合
        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }
        // DefaultChannelPromise 合法
        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }
        // 禁止 VoidChannelPromise
        if (!allowVoidPromise && promise instanceof VoidChannelPromise) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(VoidChannelPromise.class) + " not allowed for this operation");
        }
        // 禁止 CloseFuture
        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    /**
     * 获得下一个 Inbound 节点。对于 Inbound 事件的传播，是从 pipeline 的头部到尾部
     * @return
     */
    private AbstractChannelHandlerContext findContextInbound() {
        // 循环，向后获得一个 Inbound 节点
        AbstractChannelHandlerContext ctx = this;
        do {
            ctx = ctx.next;
        } while (!ctx.inbound);
        return ctx;
    }

    /**
     * 对于 Outbound 事件的传播，是从 pipeline 的尾部到头部
     */
    private AbstractChannelHandlerContext findContextOutbound() {
        // 循环，向前获得一个 Outbound 节点
        AbstractChannelHandlerContext ctx = this;
        do {
            ctx = ctx.prev;
        } while (!ctx.outbound);
        return ctx;
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel().voidPromise();
    }

    /**
     * 设置 ChannelHandler 已移除
     */
    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }

    /**
     * 设置 ChannelHandler 添加完成。完成后，状态有两种结果：REMOVE_COMPLETE、ADD_COMPLETE
     * 循环 + CAS 保证多线程下的安全变更 handlerState 属性
     */
    final boolean setAddComplete() {
        // 循环 + CAS 保证多线程下的安全变更 handlerState 属性
        for (;;) {
            int oldState = handlerState;
            if (oldState == REMOVE_COMPLETE) {
                return false;
            }
            // Ensure we never update when the handlerState is REMOVE_COMPLETE already.
            // oldState is usually ADD_PENDING but can also be REMOVE_COMPLETE when an EventExecutor is used that is not
            // exposing ordering guarantees.
            if (HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                return true;
            }
        }
    }

    /**
     * 设置 ChannelHandler 准备添加中
     */
    final void setAddPending() {
        // 当且仅当 INIT 可修改为 ADD_PENDING。理论来说，这是一个绝对会成功的操作
        boolean updated = HANDLER_STATE_UPDATER.compareAndSet(this, INIT, ADD_PENDING);
        assert updated; // This should always be true as it MUST be called before setAddComplete() or setRemoved().
    }

    final void callHandlerAdded() throws Exception {
        // We must call setAddComplete before calling handlerAdded. Otherwise if the handlerAdded method generates
        // any pipeline events ctx.handler() will miss them because the state will not allow it.
        // 设置 AbstractChannelHandlerContext 已添加
        if (setAddComplete()) {
            // 回调 ChannelHandler 添加完成 (added) 事件。一般来说，通过这个方法，来初始化 ChannelHandler。注意，因为这个方法的执行在 EventLoop 的线程中，所以要尽量避免执行时间过长。
            handler().handlerAdded(this);
        }
    }

    final void callHandlerRemoved() throws Exception {
        try {
            // Only call handlerRemoved(...) if we called handlerAdded(...) before.
            // 如果 handel 状态为添加完成，回调 ChannelHandler 移除完成事件。一般来说，通过这个方法，来释放 ChannelHandler 占用的资源
            // 注意，因为这个方法的执行在 EventLoop 的线程中，所以要尽量避免执行时间过长
            if (handlerState == ADD_COMPLETE) {
                handler().handlerRemoved(this);
            }
        } finally {
            // Mark the handler as removed in any case.
            // 设置 ChannelHandler 已移除
            setRemoved();
        }
    }

    /**
     * Makes best possible effort to detect if {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called
     * yet. If not return {@code false} and if called or could not detect return {@code true}.
     *
     * If this method returns {@code false} we will not invoke the {@link ChannelHandler} but just forward the event.
     * This is needed as {@link DefaultChannelPipeline} may already put the {@link ChannelHandler} in the linked-list
     * but not called {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}.
     *
     * 判断是否是符合的 ChannelHandler
     */
    private boolean invokeHandler() {
        // Store in local variable to reduce volatile reads.
        // 对于 ordered = true 的节点，必须 ChannelHandler 已经添加完成，对于 ordered = false 的节点，没有 ChannelHandler 的要求
        int handlerState = this.handlerState;
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return channel().attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return channel().hasAttr(key);
    }

    private static boolean safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) {
        try {
            // 提交 EventLoop 的线程中，进行执行任务
            executor.execute(runnable);
            return true;
        } catch (Throwable cause) {
            try {
                // 发生异常，回调通知 promise 相关的异常
                promise.setFailure(cause);
            } finally {
                // 释放 msg 相关的资源
                if (msg != null) {
                    ReferenceCountUtil.release(msg);
                }
            }
            return false;
        }
    }

    @Override
    public String toHintString() {
        return '\'' + name + "' will handle the message from this point.";
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ChannelHandlerContext.class) + '(' + name + ", " + channel() + ')';
    }

    /**
     * AbstractWriteTask，实现 Runnable 接口，写入任务抽象类。它有两个子类实现：WriteTask，write 任务实现类。WriteAndFlushTask，write + flush 任务实现类。它们都是 AbstractChannelHandlerContext 的内部静态类。
     */
    abstract static class AbstractWriteTask implements Runnable {

        /**
         * 提交任务时，是否计算 AbstractWriteTask 对象的自身占用内存大小
         */
        private static final boolean ESTIMATE_TASK_SIZE_ON_SUBMIT =
                SystemPropertyUtil.getBoolean("io.netty.transport.estimateSizeOnSubmit", true);

        /**
         * 每个 AbstractWriteTask 对象自身占用内存的大小。为什么占用的 48 字节呢？
         * - 16 bytes object header，对象头，16 字节。
         * - 3 reference fields，3 个对象引用字段，3 * 8 = 24 字节。
         * - 1 int fields，1 个 int 字段，4 字节。
         * padding ，补齐 8 字节的整数倍，因此 4 字节。
         * 因此，合计 48 字节 (64 位的 JVM 虚拟机，并且不考虑压缩)。
         */
        // Assuming a 64-bit JVM, 16 bytes object header, 3 reference fields and one int field, plus alignment
        private static final int WRITE_TASK_OVERHEAD =
                SystemPropertyUtil.getInt("io.netty.transport.writeTaskSizeOverhead", 48);

        /**
         * Recycler 处理器。而 Recycler 是 Netty 用来实现对象池的工具类。在网络通信中，写入是非常频繁的操作，因此通过 Recycler 重用 AbstractWriteTask 对象，减少对象的频繁创建，降低 GC 压力，提升性能。
         */
        private final Recycler.Handle<AbstractWriteTask> handle;

        /**
         * pipeline 中的节点
         */
        private AbstractChannelHandlerContext ctx;

        /**
         * 消息 (数据)
         */
        private Object msg;

        /**
         * Promise 对象
         */
        private ChannelPromise promise;

        /**
         * 对象大小
         */
        private int size;

        @SuppressWarnings("unchecked")
        private AbstractWriteTask(Recycler.Handle<? extends AbstractWriteTask> handle) {
            this.handle = (Recycler.Handle<AbstractWriteTask>) handle;
        }

        /**
         * 初始化 AbstractWriteTask 对象，AbstractWriteTask 对象是从 Recycler 中获取，所以获取完成后，需要通过该方法，初始化该对象的属性。
         */
        protected static void init(AbstractWriteTask task, AbstractChannelHandlerContext ctx,
                                   Object msg, ChannelPromise promise) {
            task.ctx = ctx;
            task.msg = msg;
            task.promise = promise;

            // 计算 AbstractWriteTask 对象大小
            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                task.size = ctx.pipeline.estimatorHandle().size(msg) + WRITE_TASK_OVERHEAD;
                // 增加 ChannelOutboundBuffer 的 totalPendingSize 属性
                ctx.pipeline.incrementPendingOutboundBytes(task.size);
            } else {
                task.size = 0;
            }
        }

        @Override
        public final void run() {
            try {
                // 减少 ChannelOutboundBuffer 的 totalPendingSize 属性
                decrementPendingOutboundBytes();
                // 执行 write 事件到下一个节点
                write(ctx, msg, promise);
            } finally {
                // 置空 AbstractWriteTask 对象，并调用 Recycler.Handle#recycle(this) 方法，回收该对象。
                recycle();
            }
        }

        void cancel() {
            try {
                decrementPendingOutboundBytes();
            } finally {
                recycle();
            }
        }

        private void decrementPendingOutboundBytes() {
            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                // 减少 ChannelOutboundBuffer 的 totalPendingSize 属性
                ctx.pipeline.decrementPendingOutboundBytes(size);
            }
        }

        private void recycle() {
            // Set to null so the GC can collect them directly
            // 置空，help gc
            ctx = null;
            msg = null;
            promise = null;
            // 回收对象
            handle.recycle(this);
        }

        protected void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.invokeWrite(msg, promise);
        }
    }

    /**
     * WriteTask，实现 SingleThreadEventLoop.NonWakeupRunnable 接口，继承 AbstractWriteTask 抽象类，write 任务实现类。
     * 为什么会实现 SingleThreadEventLoop.NonWakeupRunnable 接口呢？write 操作，仅仅将数据写到内存队列中，无需唤醒 EventLoop，从而提升性能。
     * WriteTask 无需实现 #write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) 方法，直接重用父类该方法即可。
     */
    static final class WriteTask extends AbstractWriteTask implements SingleThreadEventLoop.NonWakeupRunnable {

        private static final Recycler<WriteTask> RECYCLER = new Recycler<WriteTask>() {
            @Override
            protected WriteTask newObject(Handle<WriteTask> handle) {
                // 创建 WriteTask 对象
                return new WriteTask(handle);
            }
        };

        /**
         * 创建 WriteTask 对象
         */
        static WriteTask newInstance(
                AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            // 从 Recycler 的对象池中获得 WriteTask 对象
            WriteTask task = RECYCLER.get();
            // 初始化 WriteTask 对象的属性
            init(task, ctx, msg, promise);
            return task;
        }

        private WriteTask(Recycler.Handle<WriteTask> handle) {
            super(handle);
        }
    }

    /**
     * WriteAndFlushTask，继承 WriteAndFlushTask 抽象类，write + flush 任务实现类。
     */
    static final class WriteAndFlushTask extends AbstractWriteTask {

        private static final Recycler<WriteAndFlushTask> RECYCLER = new Recycler<WriteAndFlushTask>() {
            @Override
            protected WriteAndFlushTask newObject(Handle<WriteAndFlushTask> handle) {
                // 创建 WriteAndFlushTask 对象
                return new WriteAndFlushTask(handle);
            }
        };

        /**
         * 创建 WriteAndFlushTask 对象
         */
        static WriteAndFlushTask newInstance(
                AbstractChannelHandlerContext ctx, Object msg,  ChannelPromise promise) {
            // 从 Recycler 的对象池中获得 WriteAndFlushTask 对象
            WriteAndFlushTask task = RECYCLER.get();
            // 初始化 WriteAndFlushTask 对象的属性
            init(task, ctx, msg, promise);
            return task;
        }

        private WriteAndFlushTask(Recycler.Handle<WriteAndFlushTask> handle) {
            super(handle);
        }

        /**
         * #write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) 方法，在父类的该方法的基础上，增加执行 flush 事件到下一个节点。
         */
        @Override
        public void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            // 执行 write 事件到下一个节点
            super.write(ctx, msg, promise);
            // 执行 flush 事件到下一个节点
            ctx.invokeFlush();
        }
    }

    private static final class Tasks {
        private final AbstractChannelHandlerContext next;

        /**
         * 执行 Channel ReadComplete 事件的任务
         */
        private final Runnable invokeChannelReadCompleteTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelReadComplete();
            }
        };

        /**
         * 执行 Channel Read 事件的任务
         */
        private final Runnable invokeReadTask = new Runnable() {
            @Override
            public void run() {
                next.invokeRead();
            }
        };

        /**
         * 执行 Channel WritableStateChanged 事件的任务
         */
        private final Runnable invokeChannelWritableStateChangedTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelWritabilityChanged();
            }
        };

        /**
         * 执行 flush 事件的任务
         */
        private final Runnable invokeFlushTask = new Runnable() {
            @Override
            public void run() {
                next.invokeFlush();
            }
        };

        Tasks(AbstractChannelHandlerContext next) {
            this.next = next;
        }
    }
}
