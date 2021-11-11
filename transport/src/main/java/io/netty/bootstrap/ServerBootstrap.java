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
package io.netty.bootstrap;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 * 实现 AbstractBootstrap 抽象类，用于 Server 的启动器实现类
 *
 * 在 Server 接受一个 Client 的连接后，会创建一个对应的 Channel 对象。因此，我们看到 ServerBootstrap 的 childOptions、childAttrs、childGroup、childHandler 属性，
 * 都是这种 Channel 的可选项集合、属性集合、EventLoopGroup 对象、处理器
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    /**
     * 子 Channel 的可选项集合
     */
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();

    /**
     * 子 Channel 的属性集合
     */
    private final Map<AttributeKey<?>, Object> childAttrs = new LinkedHashMap<AttributeKey<?>, Object>();

    /**
     * 启动类配置对象
     */
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);

    /**
     * 子 Channel 的 EventLoopGroup 对象
     */
    private volatile EventLoopGroup childGroup;

    /**
     * 子 Channel 的处理器
     */
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        synchronized (bootstrap.childAttrs) {
            childAttrs.putAll(bootstrap.childAttrs);
        }
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     *
     * 当只传入一个 EventLoopGroup 对象时，即调用的是 #group(EventLoopGroup group) 时，group 和 childGroup 使用同一个。一般情况下，我们不使用这个方法
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        if (childGroup == null) {
            throw new NullPointerException("childGroup");
        }
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = childGroup;
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     *
     * 设置子 Channel 的可选项
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        if (childOption == null) {
            throw new NullPointerException("childOption");
        }
        if (value == null) {
            // 空，意味着移除
            synchronized (childOptions) {
                childOptions.remove(childOption);
            }
        } else {
            // 非空，进行修改
            synchronized (childOptions) {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     *
     * 设置子 Channel 的属性
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        if (childKey == null) {
            throw new NullPointerException("childKey");
        }
        if (value == null) {
            // 空，意味着移除
            childAttrs.remove(childKey);
        } else {
            // 非空，进行修改
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     *
     * 设置子 Channel 的处理器
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        if (childHandler == null) {
            throw new NullPointerException("childHandler");
        }
        this.childHandler = childHandler;
        return this;
    }

    @Override
    void init(Channel channel) throws Exception {
        // 将启动器配置的可选项集合，调用 #setChannelOptions(channel, options, logger) 方法，设置到 Channel 的可选项集合中
        final Map<ChannelOption<?>, Object> options = options0();
        synchronized (options) {
            setChannelOptions(channel, options, logger);
        }

        // 将启动器配置的属性集合，设置到 Channel 的属性集合中
        final Map<AttributeKey<?>, Object> attrs = attrs0();
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
                @SuppressWarnings("unchecked")
                AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
                channel.attr(key).set(e.getValue());
            }
        }

        ChannelPipeline p = channel.pipeline();

        // 记录启动器配置的子 Channel 的属性，用于创建 ServerBootstrapAcceptor 对象。
        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs;
        synchronized (childOptions) {
            currentChildOptions = childOptions.entrySet().toArray(newOptionArray(0));
        }
        synchronized (childAttrs) {
            currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(0));
        }

        // 添加 ChannelInitializer 对象到 pipeline 中，用于后续初始化 ChannelHandler 到 pipeline 中。
        // 该 ChannelInitializer 的初始化的执行，在 AbstractChannel#register0(ChannelPromise promise) 方法中触发执行
        // 那么为什么要使用 ChannelInitializer 进行处理器的初始化呢？而不直接添加到 pipeline 中?
        // 因为此时 Channel 并未注册到 EventLoop 中。如果调用 EventLoop#execute(Runnable runnable) 方法，
        // 会抛出 Exception in thread "main" java.lang.IllegalStateException: channel not registered to an event loop 异常
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                // 添加配置的 ChannelHandler 到 pipeline 中。
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    // 添加启动器配置的 ChannelHandler 到 pipeline 中
                    pipeline.addLast(handler);
                }

                // 添加 ServerBootstrapAcceptor 到 pipeline 中。
                // 使用 EventLoop 执行的原因，参见 https://github.com/lightningMan/netty/commit/4638df20628a8987c8709f0f8e5f3679a914ce1a
                // 为什么使用 EventLoop 执行添加的过程？如果启动器配置的处理器，并且 ServerBootstrapAcceptor 不使用 EventLoop 添加，则会导致 ServerBootstrapAcceptor 添加到配置的处理器之前
                // ServerBootstrapAcceptor 也是一个 ChannelHandler 实现类，用于接受客户端的连接请求
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    /**
     * 校验配置是否正确
     *
     * @return
     */
    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private static Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Map.Entry[size];
    }

    /**
     * ServerBootstrapAcceptor，继承 ChannelInboundHandlerAdapter 类，服务器接收器 (acceptor)，负责将接受的客户端的 NioSocketChannel 注册到 EventLoop 中。
     * 另外，从继承的是 ChannelInboundHandlerAdapter 类，可以看出它是 Inbound 事件处理器
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup;
        private final ChannelHandler childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        /**
         * 自动恢复接受客户端连接的任务
         */
        private final Runnable enableAutoReadTask;

        /**
         * 在服务端的启动过程中，我们看到 ServerBootstrapAcceptor 注册到服务端的 NioServerSocketChannel 的 pipeline 的尾部
         */
        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * 将接受的客户端的 NioSocketChannel 注册到 EventLoop 中
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 接受的客户端的 NioSocketChannel 对象
            final Channel child = (Channel) msg;

            // 将配置的子 Channel 的处理器，添加到 NioSocketChannel 中
            child.pipeline().addLast(childHandler);

            // 设置 NioSocketChannel 的配置项
            setChannelOptions(child, childOptions, logger);

            // 设置 NioSocketChannel 的属性
            for (Entry<AttributeKey<?>, Object> e: childAttrs) {
                child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }

            try {
                // 注册客户端的 NioSocketChannel 到 work EventLoop 中
                // 将客户端的 NioSocketChannel 对象，从 worker EventLoopGroup 中选择一个 EventLoop，注册到其上，在注册完成之后，该 worker EventLoop 就会开始轮询该客户端是否有数据写入
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        // 注册失败，强制关闭客户端的 NioSocketChannel 连接
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                // 发生异常，强制关闭客户端的 NioSocketChannel 连接
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        /**
         * 当捕获到异常时，暂停 1 秒，不再接受新的客户端连接；而后，再恢复接受新的客户端连接。
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                // 关闭接受新的客户端连接
                config.setAutoRead(false);
                // 发起 1 秒的延迟任务，恢复重新开启接受新的客户端连接
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            // 继续传播 exceptionCaught 给下一个节点
            ctx.fireExceptionCaught(cause);
        }
    }

    /**
     * 克隆 ServerBootstrap 对象
     *
     * @return
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        return copiedMap(childOptions);
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
