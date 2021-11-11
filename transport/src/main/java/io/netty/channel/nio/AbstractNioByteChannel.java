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
import io.netty.channel.*;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        // 传入的 SelectionKey 的值为 OP_READ
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    /**
     * 可通过 ALLOW_HALF_CLOSURE 配置项开启。
     * Netty 参数，一个连接的远端关闭时本地端是否关闭，默认值为 false。
     * 值为 false 时，连接自动关闭。
     * 值为 true 时，触发 ChannelInboundHandler 的 #userEventTriggered() 方法，事件 ChannelInputShutdownEvent。
     */
    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    /**
     * NioByteUnsafe，实现 AbstractNioUnsafe 抽象类，AbstractNioByteChannel 的 Unsafe 实现类
     */
    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            // 判断是否关闭 Channel 数据的读取
            // 若 isInputShutdown0() 返回 false，则说明是远端连接已经关闭了（javaChannel().socket().isInputShutdown() || !isActive()）
            if (!isInputShutdown0()) {
                // 一个连接远端关闭时本地端是否关闭，默认值为 False。值为 False 时，连接自动关闭；
                // 为 True 时，触发 ChannelInboundHandler 的 userEventTriggered() 方法，事件为 ChannelInputShutdownEvent。
                // 判断是否开启连接半关闭的功能
                if (isAllowHalfClosure(config())) {
                    // 在客户端或者服务端通过 socket.shutdownOutput() 都是单向关闭的，即关闭客户端的输出流并不会关闭服务端的输出流，所以是一种单方向的关闭流；
                    // 通过 socket.shutdownOutput() 关闭输出流，但 socket 仍然是连接状态，连接并未关闭
                    // 如果直接关闭输入或者输出流，即：in.close() 或者 out.close()，会直接关闭 socket
                    // 关闭 Channel 数据的读取
                    shutdownInput();
                    // 触发 ChannelInputShutdownEvent.INSTANCE 事件到 pipeline 中
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    // 关闭客户端的 Channel
                    close(voidPromise());
                }
            } else {
                // 已经没有数据可读取，触发通道输入关闭事件
                inputClosedSeenErrorOnRead = true;
                // 触发 ChannelInputShutdownEvent.INSTANCE 事件到 pipeline 中
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        /**
         * 处理异常
         */
        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            // byteBuf 非空，说明在发生异常之前，至少申请 ByteBuf 对象是成功的
            if (byteBuf != null) {
                // 判断 ByteBuf 对象是否可读，即剩余可读的字节数据。即 this.writerIndex - this.readerIndex > 0
                if (byteBuf.isReadable()) {
                    readPending = false;
                    // 触发 Channel read 事件到 pipeline 中。
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    // ByteBuf 对象不可读，释放 ByteBuf 对象
                    byteBuf.release();
                }
            }
            // 读取完成
            allocHandle.readComplete();
            // 触发 Channel readComplete 事件到 pipeline 中。
            pipeline.fireChannelReadComplete();
            // 触发 exceptionCaught 事件到 pipeline 中。
            // 一般情况下，我们会在自己的 Netty 应用程序中，自定义 ChannelHandler 处理异常，如果没有自定义 ChannelHandler 进行处理，最终会被 pipeline 中的尾节点 TailContext 所处理
            // 打印告警日志，调用 ReferenceCountUtil#release(Object msg) 方法，释放和异常相关的资源
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        /**
         * 读取新的写入数据。在客户端主动关闭时，服务端会收到一个 SelectionKey.OP_READ 事件的就绪，在调用客户端对应在服务端的 SocketChannel 的 #read() 方法会返回 -1，从而实现在服务端关闭客户端的逻辑。
         */
        @Override
        public final void read() {
            final ChannelConfig config = config();
            // 若 inputClosedSeenErrorOnRead = true，会主动移除对 SelectionKey.OP_READ 事件的感兴趣，避免空轮询。
            if (shouldBreakReadReady(config)) {
                // 移除对 SelectionKey.OP_READ 事件的感兴趣
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            // 获得 RecvByteBufAllocator.Handle 对象
            final ByteBufAllocator allocator = config.getAllocator();
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            // 重置 RecvByteBufAllocator.Handle 对象
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            // 是否关闭连接
            boolean close = false;
            try {
                // while 循环，读取新的写入数据
                do {
                    // 申请 ByteBuf 对象
                    byteBuf = allocHandle.allocate(allocator);
                    // 读取数据
                    // 设置最后读取字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    // 如果最后读取的字节为小于 0，说明对端已经关闭
                    if (allocHandle.lastBytesRead() <= 0) {
                        // 未读取到数据
                        // nothing was read. release the buffer.
                        // 释放 ByteBuf 对象
                        byteBuf.release();
                        // 置空 ByteBuf 对象
                        byteBuf = null;
                        // 如果最后读取的字节为小于 0，说明对端已经关闭
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        // 结束循环
                        break;
                    }

                    // 读取消息数量 + 1
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    // 触发 Channel read 事件到 pipeline 中。
                    // 一般情况下，我们会在自己的 Netty 应用程序中，自定义 ChannelHandler 处理读取到的数据。当然，此时读取的数据，大多数情况下是需要在解码 (Decode)。
                    // 如果没有自定义 ChannelHandler 进行处理，最终会被 pipeline 中的尾节点 TailContext 所处理，最终也会释放 ByteBuf 对象。
                    pipeline.fireChannelRead(byteBuf);
                    // 置空 ByteBuf 对象
                    byteBuf = null;
                } while (allocHandle.continueReading());// 判断循环是否继续，读取新的数据

                // 读取完成
                allocHandle.readComplete();
                // 触发 Channel readComplete 事件到 pipeline 中。
                // 如果有需要，可以自定义处理器，处理该事件。一般情况下，不需要。如果没有自定义 ChannelHandler 进行处理，最终会被 pipeline 中的尾节点 TailContext 所处理，具体的调用是空方法
                pipeline.fireChannelReadComplete();

                // 关闭客户端的连接
                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                // 处理异常
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    /**
     * 过滤写入的消息 (数据)
     */
    @Override
    protected final Object filterOutboundMessage(Object msg) {
        // ByteBuf 的情况
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 已经是内存 ByteBuf
            if (buf.isDirect()) {
                return msg;
            }

            // 非内存 ByteBuf，需要进行创建封装
            // 消息 (数据) 是 ByteBuf 类型，如果是非 Direct ByteBuf 对象，需要调用 #newDirectBuffer(ByteBuf) 方法，复制封装成 Direct ByteBuf 对象。
            // 原因是：在使用 Socket 传递数据时性能很好，由于数据直接在内存中，不存在从 JVM 拷贝数据到直接缓冲区的过程，性能好。
            return newDirectBuffer(buf);
        }

        // FileRegion 的情况
        if (msg instanceof FileRegion) {
            // 消息 (数据) 是 FileRegion 类型，直接返回
            return msg;
        }

        // 不支持其他数据类型
        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        // true，注册对 SelectionKey.OP_WRITE 事件感兴趣
        if (setOpWrite) {
            setOpWrite();
        } else {
            // false，取消对 SelectionKey.OP_WRITE 事件感兴趣
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            // 立即发起下一次 flush 任务
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * 抽象方法，读取写入的数据到方法参数 buf 中。返回值为读取到的字节数，当返回值小于 0 时，表示对端已经关闭。
     *
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        // 合法
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        // 注册对 SelectionKey.OP_WRITE 事件感兴趣
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        // 合法
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        // 若注册了 SelectionKey.OP_WRITE，则进行取消
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
