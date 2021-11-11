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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    private static final ClosedChannelException DO_CLOSE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractNioChannel.class, "doClose()");

    private final SelectableChannel ch;
    protected final int readInterestOp;
    volatile SelectionKey selectionKey;
    boolean readPending;
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     *
     * 目前正在连接远程地址的 ChannelPromise 对象。
     */
    private ChannelPromise connectPromise;

    /**
     * 连接超时监听 ScheduledFuture 对象。
     */
    private ScheduledFuture<?> connectTimeoutFuture;

    /**
     * 正在连接的远程地址
     */
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent);
        // Netty NIO Channel 对象，持有的 Java 原生 NIO 的 Channel 对象
        this.ch = ch;
        // 感兴趣事件的操作位值
        this.readInterestOp = readInterestOp;
        try {
            // 设置 NIO Channel 为非阻塞。
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                // 若发生异常，关闭 NIO Channel，并抛出异常
                ch.close();
            } catch (IOException e2) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "Failed to close a partially initialized socket.", e2);
                }
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * 在 EventLoop 的线程中，调用 AbstractNioUnsafe#clearReadPending0() 方法，移除对"读"事件的感兴趣 (对于 NioServerSocketChannel 的"读"事件就是 SelectionKey.OP_ACCEPT)
     * <p>
     * Set read pending to {@code false}.
     */
    protected final void clearReadPending() {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                clearReadPending0();
            } else {
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    private void clearReadPending0() {
        readPending = false;
        // 移除对"读"事件的感兴趣
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            // 忽略，如果 SelectionKey 不合法，例如已经取消
            if (!key.isValid()) {
                return;
            }
            // 移除对"读"事件的感兴趣
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                // 通过取反求并，后调用 SelectionKey#interestOps(interestOps) 方法，仅移除对"读"事件的感兴趣
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        /**
         * 执行 Channel 连接远程地址的逻辑
         *
         * @param remoteAddress
         * @param localAddress
         * @param promise
         */
        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            // 确保 java 原生的 channel 是打开的
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                // 目前有正在连接远程地址的 ChannelPromise，则直接抛出异常，禁止同时发起多个连接。
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }
                // 记录 Channel 是否激活，判断 SocketChannel 是否处于打开，并且连接的状态
                boolean wasActive = isActive();

                // 执行连接远程地址
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    // 记录 connectPromise，connectPromise 变量，在 AbstractNioChannel 类中定义
                    connectPromise = promise;
                    // 记录 requestedRemoteAddress，requestedRemoteAddress 变量，在 AbstractNioChannel 类中定义
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) {
                        // 调用 EventLoop#schedule(Runnable command, long delay, TimeUnit unit) 方法，发起定时任务 connectTimeoutFuture，监听连接远程地址超时。若连接超时，则回调通知 connectPromise 超时异常。
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    // 关闭 channel
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }
                    // 调用 ChannelPromise#addListener(ChannelFutureListener) 方法，添加监听器，监听连接远程地址是否取消。若取消，则取消 connectTimeoutFuture 超时任务，并置空 connectPromise，这样客户端 Channel 可以发起下一次连接。
                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isCancelled()) {
                                // 取消定时任务
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                // 置空 connectPromise
                                connectPromise = null;
                                // 关闭 channel
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                // 回调通知 promise 发生异常
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        /**
         * 通知 connectPromise 连接完成
         *
         * @param promise
         * @param wasActive
         */
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            // 获得 Channel 是否激活
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            // 回调通知 promise 执行成功，此处的通知，对应回调的是我们添加到 #connect(...) 方法返回的 ChannelFuture 的 ChannelFutureListener 的监听器
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            // 若 Channel 是新激活的，触发通知 Channel 已激活的事件。后续的流程，和 NioServerSocketChannel 一样，
            // 也就说，会调用到 AbstractUnsafe#beginRead() 方法。这意味着什么呢？将我们创建 NioSocketChannel 时，设置的 readInterestOp = SelectionKey.OP_READ 添加为感兴趣的事件。也就说，客户端可以读取服务端发送来的数据。
            if (!wasActive && active) {
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            // 如果用户取消了连接尝试，则关闭通道，然后触发 channelInactive()
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            // 回调通知 promise 发生异常
            promise.tryFailure(cause);
            // 如果原生 channel 关闭，则关闭 netty channel
            closeIfClosed();
        }

        /**
         * 完成客户端的连接
         */
        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.
            // 判断是否在 EventLoop 的线程中。
            assert eventLoop().inEventLoop();

            try {
                // 获得 Channel 是否激活，调试时，此时返回 false，因为连接还没完成
                boolean wasActive = isActive();
                // 执行完成连接的逻辑
                doFinishConnect();
                // 执行完成连接成功，通知 connectPromise 连接完成
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                // 执行完成连接异常，通知 connectPromise 连接异常
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                // 执行完成连接结束，取消 connectTimeoutFuture 超时任务
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel(false);
                }
                // 置空 connectPromise
                connectPromise = null;
            }
        }

        /**
         * AbstractNioUnsafe 重写了 #flush0() 方法
         */
        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            // 判断是否已经处于 flush 准备中
            if (!isFlushPending()) {
                super.flush0();
            }
        }

        /**
         * 通过 Selector 轮询到 Channel 的 OP_WRITE 就绪时，调用 AbstractNioUnsafe#forceFlush() 方法，强制 flush
         */
        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            // 在完成强制 flush 之后，会取消对 SelectionKey.OP_WRITE 事件的感兴趣
            super.flush0();
        }

        /**
         * 正常情况下，在异常情况下会有所不同。我们知道，Channel 大多数情况下是可写的，所以不需要专门去注册 SelectionKey.OP_WRITE 事件。所以在 Netty 的实现中，
         * 默认 Channel 是可写的，当写入失败的时候，再去注册 SelectionKey.OP_WRITE 事件。这意味着什么呢？在 #flush() 方法中，如果写入数据到 Channel 失败，
         * 会通过注册 SelectionKey.OP_WRITE 事件，然后在轮询到 Channel 可写时，再 "回调" #forceFlush() 方法。
         * <p>
         * 这就是这段代码的目的，如果处于对 SelectionKey.OP_WRITE 事件感兴趣，说明 Channel 此时是不可写的，那么调用父类 AbstractUnsafe 的 #flush0() 方法，也没有意义，所以就不调用。
         */
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            // 合法
            return selectionKey.isValid()
                    // 对 SelectionKey.OP_WRITE 事件不感兴趣
                    && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try {
                // 将 nio channel 绑定到 eventLoop 的 unwrappedSelector 上，并添加附件为 netty channel
                // 每个 NioEventLoop 对象上，都独有一个 Selector 对象，注册 Java 原生 NIO 的 Channel 对象到 Selector 对象上
                // 注册方式是多态的，它既可以被 NIOServerSocketChannel 用来监听客户端的连接接入，也可以注册 SocketChannel 用来监听网络读或者写操作。
                // 通过 SelectionKey#interestOps(int ops) 方法可以方便地修改监听操作位。所以，此处注册需要获取 SelectionKey 并给 AbstractNIOChannel 的成员变量 selectionKey 赋值。
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    eventLoop().selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    /**
     * 执行取消注册
     */
    @Override
    protected void doDeregister() throws Exception {
        // 调用 EventLoop#cancel(SelectionKey key) 方法，取消 SelectionKey，即相当于调用 SelectionKey#cancel() 方法。如此，对通道的读写等等 IO 就绪事件不再感兴趣，也不会做出相应的处理。
        eventLoop().cancel(selectionKey());
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        final SelectionKey selectionKey = this.selectionKey;
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;

        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
            // 将我们创建 NioServerSocketChannel 时，设置的 readInterestOp = SelectionKey.OP_ACCEPT 添加为感兴趣的事件，也就说，服务端可以开始处理客户端的连接事件
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    /**
     * 适用于客户端正在发起对服务端的连接的阶段。
     */
    @Override
    protected void doClose() throws Exception {
        // 通知 connectPromise 异常失败
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(DO_CLOSE_CLOSED_CHANNEL_EXCEPTION);
            connectPromise = null;
        }

        // 取消 connectTimeoutFuture 等待
        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
