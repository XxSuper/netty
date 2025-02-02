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
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 *
 * AbstractBootstrap 是个抽象类，并且实现 Cloneable 接口。另外，它声明了 B 、C 两个泛型：
 * B: 继承 AbstractBootstrap 类，用于表示自身的类型。
 * C: 继承 Channel 类，表示表示创建的 Channel 类型
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    /**
     * EventLoopGroup 对象
     */
    volatile EventLoopGroup group;

    /**
     * Channel 工厂，用于创建 Channel 对象。
     */
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;

    /**
     * 本地地址
     */
    private volatile SocketAddress localAddress;

    /**
     * 可选项集合
     */
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();

    /**
     * 属性集合
     */
    private final Map<AttributeKey<?>, Object> attrs = new LinkedHashMap<AttributeKey<?>, Object>();

    /**
     * 处理器
     */
    private volatile ChannelHandler handler;

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        // 使用 synchronized 修饰符，防止传入的 bootstrap 参数的 options 和 attrs 属性，在另外的线程被修改
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        synchronized (bootstrap.attrs) {
            attrs.putAll(bootstrap.attrs);
        }
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     *
     * 设置 EventLoopGroup 到 group 中
     */
    public B group(EventLoopGroup group) {
        if (group == null) {
            throw new NullPointerException("group");
        }
        // 不允许重复设置
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        this.group = group;
        // 最终调用 #self() 方法，返回自己，“链式调用”
        return self();
    }

    /**
     * 返回自己，这里就使用到了 AbstractBootstrap 声明的 B 泛型
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     *
     * 设置要被实例化的 Channel 的类
     */
    public B channel(Class<? extends C> channelClass) {
        if (channelClass == null) {
            throw new NullPointerException("channelClass");
        }
        // 传入的 channelClass 参数，会使用 io.netty.channel.ReflectiveChannelFactory 进行封装，
        // 调用 #channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) 方法，设置 channelFactory 属性
        return channelFactory(new ReflectiveChannelFactory<C>(channelClass));
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        if (channelFactory == null) {
            throw new NullPointerException("channelFactory");
        }
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }

        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * {@link io.netty.channel.ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} to
     * simplify your code.
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     *
     * 设置创建 Channel 的本地地址
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     *
     * 设置创建 Channel 的可选项
     */
    public <T> B option(ChannelOption<T> option, T value) {
        if (option == null) {
            throw new NullPointerException("option");
        }
        if (value == null) {
            // 空，意味着移除
            synchronized (options) {
                options.remove(option);
            }
        } else {
            // 非空，进行修改
            synchronized (options) {
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     *
     * 设置创建 Channel 的属性，attr 可以理解成 java.nio.channels.SelectionKey 的 attachment 属性，并且类型为 Map
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (value == null) {
            // 空，意味着移除
            synchronized (attrs) {
                attrs.remove(key);
            }
        } else {
            // 非空，进行修改
            synchronized (attrs) {
                attrs.put(key, value);
            }
        }
        return self();
    }

    /**
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     *
     * 校验配置是否正确，在 #bind(...) 方法中，绑定本地地址时，会调用该方法进行校验
     */
    public B validate() {
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     *
     * 抽象方法，克隆一个 AbstractBootstrap 对象，来自实现 Cloneable 接口，在子类中实现。这是深拷贝，即创建一个新对象，但不是所有的属性是深拷贝，
     * 浅拷贝属性：group、channelFactory、handler、localAddress
     * 深拷贝属性：options、attrs
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     *
     * 绑定端口，启动服务端，该方法返回的是 ChannelFuture 对象，也就是异步的绑定端口，启动服务端，如果需要同步，则需要调用 ChannelFuture#sync() 方法
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        // 校验服务启动需要的必要参数
        validate();
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        // 绑定本地地址(包括端口)
        return doBind(localAddress);
    }

    private ChannelFuture doBind(final SocketAddress localAddress) {
        // 初始化并注册一个 Channel 对象，因为注册是异步的过程，所以返回一个 ChannelFuture 对象。
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        // 若发生异常，直接进行返回
        if (regFuture.cause() != null) {
            return regFuture;
        }

        // 因为注册是异步的过程，有可能已完成，有可能未完成
        if (regFuture.isDone()) {
            // 处理已完成情况
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise();
            // 绑定 Channel 的端口，并注册 Netty Channel 到 SelectionKey 中
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // 处理未完成的情况
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);

            // 如果异步注册对应的 ChanelFuture 未完成，则调用 ChannelFuture#addListener(ChannelFutureListener) 方法，添加监听器，在注册完成后，进行回调执行 #doBind0(...) 方法的逻辑
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();

                        // 绑定
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 初始化并注册一个 Channel 对象，并返回一个 ChannelFuture 对象
     * Bootstrap 与 ServerBootstrap 都继承自 AbstractBootstrap 抽象类，所以 #initAndRegister() 方法的流程上是一致的。Bootstrap 和 ServerBootstrap 的差别在于：
     * 1、创建的 Channel 对象不同。
     * 2、初始化 Channel 配置的代码实现不同。
     *
     * @return
     */
    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            // 创建 Channel 对象，会使用 ReflectiveChannelFactory 创建 NioServerSocketChannel 对象
            channel = channelFactory.newChannel();
            // 初始化 Channel 配置
            init(channel);
        } catch (Throwable t) {
            // 已创建 Channel 对象
            if (channel != null) {
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                // 强制关闭 Channel
                channel.unsafe().closeForcibly();
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                // 返回带异常的 DefaultChannelPromise 对象。因为初始化 Channel 对象失败，所以需要调用 #closeForcibly() 方法，强制关闭 Channel
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            // 返回带异常的 DefaultChannelPromise 对象。因为创建 Channel 对象失败，所以需要创建一个 FailedChannel 对象，设置到 DefaultChannelPromise 中才可以返回
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }
        // 首先获得 EventLoopGroup 对象，后调用 EventLoopGroup#register(Channel) 方法，注册 Channel 到 EventLoopGroup 中，实际在方法内部，EventLoopGroup 会分配一个 EventLoop 对象，将 Channel 注册到其上
        ChannelFuture regFuture = config().group().register(channel);
        // 为什么会有正常和强制关闭 Channel 两种不同的处理呢？调用的前提，在于 Channel 是否注册到 EventLoopGroup 成功。因为注册失败，也不好触发相关的事件。
        if (regFuture.cause() != null) {
            // 若发生异常，并且 Channel 已经注册成功，则调用 #close() 方法，正常关闭 Channel
            if (channel.isRegistered()) {
                channel.close();
            } else {
                // 若发生异常，并且 Channel 并未注册成功，则调用 #closeForcibly() 方法，强制关闭 Channel
                channel.unsafe().closeForcibly();
            }
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.

        return regFuture;
    }

    abstract void init(Channel channel) throws Exception;

    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        // 调用 EventLoop 执行 Channel 的端口绑定逻辑。但是，实际上当前线程已经是 EventLoop 所在的线程了，为何还要这样操作呢？
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                // 注册成功，绑定端口
                if (regFuture.isSuccess()) {
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    // 注册失败，回调通知 promise 异常
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     *
     * 设置创建 Channel 的处理器
     */
    public B handler(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     *
     * 返回当前 AbstractBootstrap 的配置对象
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        final Map<K, V> copied;
        synchronized (map) {
            if (map.isEmpty()) {
                return Collections.emptyMap();
            }
            copied = new LinkedHashMap<K, V>(map);
        }
        return Collections.unmodifiableMap(copied);
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        return copiedMap(options);
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    /**
     * 静态方法，设置已经创建的 Channel 的多个可选项
     * @param channel
     * @param options
     * @param logger
     */
    static void setChannelOptions(
            Channel channel, Map<ChannelOption<?>, Object> options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options.entrySet()) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    /**
     * 设置传入的 Channel 的一个可选项
     * @param channel
     * @param option
     * @param value
     * @param logger
     */
    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
