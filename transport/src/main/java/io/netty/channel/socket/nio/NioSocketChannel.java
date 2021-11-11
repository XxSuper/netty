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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);

    /**
     * 静态属性，默认的 SelectorProvider 实现类
     */
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    /**
     * 在构造方法中，调用 #newSocket(SelectorProvider provider) 方法，创建 NIO 的 SocketChannel 对象。
     *
     * @param provider
     * @return
     */
    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    /**
     * config 属性，Channel 对应的配置对象。每种 Channel 实现类，也会对应一个 ChannelConfig 实现类。例如，NioSocketChannel 类，对应 SocketChannelConfig 配置类。
     */
    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) {
        // 调用父 AbstractNioByteChannel 的构造方法
        super(parent, socket);
        // 初始化 config 属性，创建 NioSocketChannelConfig 对象
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() {
        // 判断 SocketChannel 是否处于打开，并且连接的状态
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public boolean isInputShutdown() {
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            // 关闭 Channel 数据的读取
            shutdownInput0();
            // 通知 Promise 成功
            promise.setSuccess();
        } catch (Throwable t) {
            // 通知 Promise 失败
            promise.setFailure(t);
        }
    }

    private void shutdownInput0() throws Exception {
        // 调用 Java NIO Channel 的 shutdownInput 方法
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        // 若 localAddress 非空
        if (localAddress != null) {
            // 绑定本地地址，一般情况下，NIO Client 是不需要绑定本地地址的。默认情况下，系统会随机分配一个可用的本地地址，进行绑定。
            doBind0(localAddress);
        }

        // 执行是否成功
        boolean success = false;
        try {
            // Java 原生 NIO SocketChannel 连接远程地址，并返回是否连接完成(成功)
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            // 若未连接完成，则关注连接( OP_CONNECT )事件。也就是说，当连接远程地址成功时，Channel 对应的 Selector 将会轮询到该事件，可以进一步处理。
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            // 标记执行是否成功
            success = true;
            // 返回是否连接完成
            return connected;
        } finally {
            // 执行失败，则关闭 Channel
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        // 调用 SocketChannel#finishConnect() 方法，完成连接
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    /**
     * 执行 Java 原生 NIO SocketChannel 关闭
     */
    @Override
    protected void doClose() throws Exception {
        // 执行父类关闭方法
        super.doClose();
        // 执行 Java 原生 NIO SocketChannel 关闭
        javaChannel().close();
    }

    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        // 获得 RecvByteBufAllocator.Handle 对象
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        // 设置最大可读取字节数量。因为 ByteBuf 对象目前最大写入的大小为 byteBuf.writableBytes() 的长度
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        // 读取数据到 ByteBuf 中
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        return region.transferTo(javaChannel(), position);
    }

    /**
     * 调整每次写入的最大字节数
     *
     * @param attempted                    尝试刷新的数据大小
     * @param written                      真实刷新的数据大小
     * @param oldMaxBytesPerGatheringWrite 旧的每次写入的最大字节数
     */
    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.
        // 扩大2倍。全部写满了，可能还有更多的数据需要写。
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            // 缩小2倍。attempted > 4KB 且真实写入的数据不到 attempted 的一半
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        // 获得 Java NIO 原生 SocketChannel
        SocketChannel ch = javaChannel();
        // 获得自旋写入次数
        int writeSpinCount = config().getWriteSpinCount();
        // 不断自旋写入，直到完全写入结束
        do {
            // 内存队列为空，结束循环，直接返回
            if (in.isEmpty()) {
                // 因为在 Channel 不可写的时候，会注册 SelectionKey.OP_WRITE，等待 NIO Channel 可写。而后会 "回调" #forceFlush() 方法，
                // 该方法内部也会调用 #doWrite(ChannelOutboundBuffer in) 方法。所以在完成内部队列的数据向对端写入时候，需要调用 #clearOpWrite() 方法
                // 取消对 SelectionKey.OP_WRITE 的感兴趣
                // All written so clear OP_WRITE
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // 获得每次写入的最大字节数
            // Ensure the pending writes are made of ByteBufs only.
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();
            // 从内存队列中，获得要写入的 ByteBuffer 数组。注意，如果内存队列中数据量很大，可能获得的仅仅是一部分数据。
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);
            // 获得写入的 ByteBuffer 数组的个数。
            // 为什么不直接调用数组的 #length() 方法呢？因为返回的 ByteBuffer 数组是预先生成的数组缓存，存在不断重用的情况，所以不能直接使用 #length() 方法，
            // 而是要调用 ChannelOutboundBuffer#nioBufferCount() 方法，获得写入的 ByteBuffer 数组的个数。
            int nioBufferCnt = in.nioBufferCount();

            // 写入 ByteBuffer 数组，到对端
            // Always us nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    writeSpinCount -= doWrite0(in);
                    break;
                case 1: {
                    // 调用 Java 原生 SocketChannel#write(ByteBuffer buffer) 方法，执行 NIO write 调用，写入单个 ByteBuffer 对象到对端。
                    // Only one ByteBuf so use non-gathering write
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    ByteBuffer buffer = nioBuffers[0];
                    int attemptedBytes = buffer.remaining();
                    // 执行 NIO write 调用，写入单个 ByteBuffer 对象到对端
                    final int localWrittenBytes = ch.write(buffer);
                    // 写入字节小于等于 0，说明 NIO Channel 不可写，所以注册 SelectionKey.OP_WRITE，等待 NIO Channel 可写，并返回以结束循环
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    // 调整每次写入的最大字节数
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
                    // 从内存队列中，移除已经写入的数据 (消息)
                    in.removeBytes(localWrittenBytes);
                    // 写入次数减一
                    --writeSpinCount;
                    break;
                }
                default: {
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // We limit the max amount to int above so cast is safe
                    long attemptedBytes = in.nioBufferSize();
                    // 调用 Java 原生 SocketChannel#write(ByteBuffer[] srcs, int offset, int length) 方法，执行 NIO write 调用，写入多个 ByteBuffer 对象到对端。
                    // 批量一次性写入，提升性能。
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                    // 写入字节小于等于 0，说明 NIO Channel 不可写，所以注册 SelectionKey.OP_WRITE，等待 NIO Channel 可写，并返回以结束循环
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    // 调整每次写入的最大字节数
                    // Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite);
                    // 从内存队列中，移除已经写入的数据 (消息)
                    in.removeBytes(localWrittenBytes);
                    // 写入次数减一
                    --writeSpinCount;
                    break;
                }
            }
        } while (writeSpinCount > 0);// 循环自旋写入

        // 通过 writeSpinCount < 0 来判断，内存队列中的数据未完全写入，说明 NIO Channel 不可写，所以注册 SelectionKey.OP_WRITE，等待 NIO Channel 可写
        // 举个例子，最后一次写入，Channel 的缓冲区还剩下 10 字节可写，内存队列中剩余 90 字节，那么可以成功写入 10 字节，剩余 80 字节。也就说，此时 Channel 不可写了。
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    private final class NioSocketChannelUnsafe extends NioByteUnsafe {

        /**
         * 执行准备关闭
         * <p>
         * SO_LINGER Socket 参数，关闭 Socket 的延迟时间，Netty 默认值为 -1，表示禁用该功能。
         * -1 表示 socket.close() 方法立即返回，但 Os 底层会将发送缓冲区全部发送到对端。
         * 0 表示 socket.close() 方法立即返回，但 Os 放弃发送缓冲区的数据直接向对端发送RST包，对端收到复位错误。
         * 非 0 整数值表示调用 socket.close() 方法的线程被阻塞直到延迟时间到或发送缓冲区中的数据发送完毕。若超时，则对端会收到复位错误。
         */
        @Override
        protected Executor prepareToClose() {
            try {
                // 如果配置 StandardSocketOptions.SO_LINGER 大于 0，在真正关闭 Channel，需要阻塞直到延迟时间到或发送缓冲区中的数据发送完毕。
                // 如果在 EventLoop 中执行真正关闭 Channel 的操作，那么势必会阻塞 EventLoop 的线程。所以，返回 GlobalEventExecutor.INSTANCE 对象，
                // 作为执行真正关闭 Channel 的操作的执行器 (它也有一个自己的线程)
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449
                    // 执行取消注册
                    // 为什么要调用 #doDeregister() 方法呢？
                    // 因为 SO_LINGER 大于 0 时，真正关闭 Channel，需要阻塞直到延迟时间到或发送缓冲区中的数据发送完毕。如果不取消该 Channel 的 SelectionKey.OP_READ 事件的感兴趣，
                    // 就会不断触发读事件，导致 CPU 空轮询。为什么呢？在 Channel 关闭时，会自动触发 SelectionKey.OP_READ 事件。而且，会不断的触发，如果不进行取消 SelectionKey.OP_READ 事件的感兴趣。
                    doDeregister();
                    // 如果开启 SO_LINGER 功能，返回 GlobalEventExecutor.INSTANCE 对象。
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            // 如果关闭 SO_LINGER 功能，返回 null 对象
            return null;
        }
    }

    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
            int newSendBufferSize = getSendBufferSize() << 1;
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(getSendBufferSize() << 1);
            }
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
