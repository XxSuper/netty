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

import io.netty.channel.*;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 * NioEventLoop 继承 SingleThreadEventLoop 抽象类，NIO EventLoop 实现类，实现对注册到其中的 Channel 的就绪的 IO 事件，和对用户提交的任务进行处理。
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否禁用 SelectionKey 的优化，默认开启
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * 少于该值，不开启空轮询重建新的 Selector 对象的功能
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;

    /**
     * NIO Selector 空轮询该次后，重建新的 Selector 对象，用以解决 JDK NIO 的 epoll 空轮询 Bug
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            // epoll_wait的参数timeout可以指定超时时间，selectNow传入的参数是0，也就是不超时等待立即返回
            // 可以通过 #selectNow() 方法，无阻塞的 select Channel 是否有感兴趣的 IO 就绪事件
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    // 初始化了 NioEventLoop 的静态属性
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        // 解决 Selector#open() 方法，发生 NullPointException 异常
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        // 初始化 SELECTOR_AUTO_REBUILD_THRESHOLD 属性
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * <p>
     * 包装的 NIO Selector 对象，Netty 对 NIO Selector 做了优化
     * <p>
     * {@link #openSelector()}
     */
    private Selector selector;

    /**
     * 未包装的 NIO Selector 对象
     */
    private Selector unwrappedSelector;

    /**
     * 注册的 NIO SelectionKey 集合。Netty 自己实现，经过优化。
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * NIO SelectorProvider 对象，用于创建 NIO Selector 对象
     */
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     *
     * 唤醒标记。因为唤醒方法 {@link Selector#wakeup()} 开销比较大，通过该标识，减少调用。
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    /**
     * Select 策略
     *
     * @see #select(boolean)
     */
    private final SelectStrategy selectStrategy;

    /**
     * 在 NioEventLoop 中，会有三种类型的任务：1) Channel 的就绪的 IO 事件；2) 普通任务；3) 定时任务；
     * 处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例
     */
    private volatile int ioRatio = 50;

    /**
     * 取消 SelectionKey 的数量
     */
    private int cancelledKeys;

    /**
     * 是否需要再次 select Selector 对象
     */
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        // 创建 NIO Selector 对象，会对Selector做优化
        final SelectorTuple selectorTuple = openSelector();
        // Selector优化后是SelectedSelectionKeySetSelector
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    private static final class SelectorTuple {

        /**
         * 未包装的 Selector 对象
         */
        final Selector unwrappedSelector;

        /**
         * 包装的 Selector 对象
         */
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 创建 Selector 对象
     *
     * @return
     */
    private SelectorTuple openSelector() {
        // 创建 Selector 对象，作为 unwrappedSelector
        final Selector unwrappedSelector;
        try {
            // 通过 provider 拿到一个原生的 selector
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }
        // 禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，SelectorTuple 的 selector 也使用 unwrappedSelector。不支持优化 selector 操作，直接返回原生的 selector
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }
        // 获得 SelectorImpl 类
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 调用 Class#forName(String name, boolean initialize, ClassLoader loader) 方法，加载 sun.nio.ch.SelectorImpl 类。加载成功，则返回该类，否则返回异常
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());// 成功，则返回该类
                } catch (Throwable cause) {
                    return cause;// 失败，则返回该异常
                }
            }
        });
        // isAssignableFrom(class2) 判定此 Class 对象所表示的类或接口与指定的 Class 参数所表示的类或接口是否相同，或是否是其超类或超接口。如果是则返回 true；否则返回 false。
        // 如果该 Class 表示一个基本类型，且指定的 Class 参数正是该 Class 对象，则该方法返回 true；否则返回 false。
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            // 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

        // 创建 SelectedSelectionKeySet 对象，这是 Netty 对 Selector 的 selectionKeys 的优化。内部为 SelectionKey 数组以及一个标示添加到数组中到 SelectionKey 的数量
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 获得 "selectedKeys" "publicSelectedKeys" 的 Field
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }
                    // 设置 Field 可访问
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    // 向 unwrappedSelector 对象的这个 selectedKeys、publicSelectedKeys 字段中设置新值为 selectedKeySet
                    // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中的 selectedKeys 和 publicSelectedKeys 属性，selectedKeys 和 publicSelectedKeys 的类型都是 HashSet
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;// 失败，则返回该异常
                } catch (IllegalAccessException e) {
                    return e;// 失败，则返回该异常
                }
            }
        });
        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        // 设置 SelectedSelectionKeySet 对象到 selectedKeys 中。是否成功优化 Selector 对象，是通过 selectedKeys 是否成功初始化来判断
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 创建 SelectedSelectionKeySetSelector 对象，这是 Netty 对 Selector 优化的实现类
        // 创建 SelectorTuple 对象。即，selector 使用 SelectedSelectionKeySetSelector 对象。
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * 创建任务队列。创建 mpsc 队列，mpsc 是 multiple producers and a single consumer 的缩写，mpsc 是对多线程生产任务，单线程消费任务的队列
     */
    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        // 实现一个无锁方式的线程安全队列，总之一句话，效率相当的高
        // mpsc 队列，即多生产者单消费者队列，netty使用 mpsc，方便的将外部线程的 task 聚集，在 reactor 线程内部用单线程来串行执行
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     *
     * 注册 Java NIO Channel (不一定需要通过 Netty 创建的 Channel) 到 Selector 上，相当于说，也注册到了 EventLoop 上
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            // 注册 Java NIO Channel 到 Selector 上。这里我们可以看到，attachment 为 NioTask 对象，而不是 Netty Channel 对象
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     *
     * 设置 ioRatio 属性
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     *
     * 用于 NIO Selector 发生 epoll bug 时，重建 Selector 对象。只允许在 EventLoop 的线程中，调用 #rebuildSelector0() 方法
     */
    public void rebuildSelector() {
        // 只允许在 EventLoop 的线程中执行
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    /**
     * 重建 Selector 对象，new 一个新的 selector，将之前注册到老的 selector 上的 channel 重新转移到新的 selector 上
     * 主要是需要将老的 Selector 对象的"数据"复制到新的 Selector 对象上，并关闭老的 Selector 对象
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            // 通过 openSelector() 方法，创建一个新的 selector 对象
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        // 遍历老的 Selector 对象的 selectionKeys，将注册在 NioEventLoop 上的所有 Channel，注册到新创建的 Selector 对象上
        int nChannels = 0;// 计算重新注册成功的 Channel 数量
        // 执行一个死循环，只要执行过程中出现过一次并发修改 selectionKeys 异常，就重新开始转移
        // 拿到所有有效的 key 并遍历
        for (SelectionKey key: oldSelector.keys()) {
            // 获取附件
            Object a = key.attachment();
            try {
                // key 失效或者已经重新绑定跳过，校验 SelectionKey 是否有效，并且 Java NIO Channel 并未注册在新的 Selector 对象上
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                // 取消老的 SelectionKey
                // 取消该 key 在旧的 selector 上的事件注册
                key.cancel();
                // 将该 key 对应的 channel 注册到新的 selector 对象
                // 将 Java NIO Channel 注册到新的 Selector 对象上，返回新的 SelectionKey 对象
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                // 修改 Channel 的 selectionKey 指向新的 SelectionKey 对象上
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    // 重新绑定 channel 和新的 key 的关系
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                // 计数 ++
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                // 当发生异常的时候，根据不同的 SelectionKey 的 attachment 来判断处理方式
                if (a instanceof AbstractNioChannel) {
                    // 当 attachment 是 Netty NIO Channel 时，调用 Unsafe#close(ChannelPromise promise) 方法，关闭发生异常的 Channel。
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    // 当 attachment 是 Netty NioTask 时，调用 #invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) 方法，通知 Channel 取消注册
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    // channel 解除了注册事件，为 inbound 事件。
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        // 修改 selector 和 unwrappedSelector 指向新的 Selector 对象
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            // 关闭老的 Selector 对象，转移完成之后，将原有的 selector 废弃，后面所有的轮询都是在新的 selector 上进行
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * NioEventLoop 运行，处理任务。reactor 线程的主体，在第一次添加任务的时候被启动
     */
    @Override
    protected void run() {
        // "死"循环，直到 NioEventLoop 关闭
        for (;;) {
            try {
                try {
                    // 获得使用的 select 策略
                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                        // 默认实现下，不存在这个情况
                    case SelectStrategy.CONTINUE:
                        continue;

                        // 进行 Selector 阻塞 select
                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        // 重置 wakeUp 标识为 false，并返回修改前的值
                        // 调用 #select(boolean oldWakeUp) 方法，选择 (查询) 任务
                        // wakenUp 表示是否应该唤醒正在阻塞的 select 操作
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).

                        // 若唤醒标识 wakeup 为 true 时，调用 Selector#wakeup() 方法，唤醒 Selector
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:// 已经有可以处理的任务
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                // IO任务、非IO任务执行的时间比例由ioRatio来控制, 通过它来限制非IO任务的执行时间, 默认值是50, 表示允许非IO任务获得和IO任务相同的执行时间
                final int ioRatio = this.ioRatio;

                // 根据 ioRatio 的配置不同，分成略有差异的 2 种：第一种，ioRatio 为 100，则不考虑时间占比的分配；第二种，ioRatio 为 < 100，则考虑时间占比的分配
                if (ioRatio == 100) {
                    try {
                        // 处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 运行所有普通任务和定时任务，不限制时间
                        runAllTasks();
                    }
                } else {
                    // 记录当前时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // 以 #processSelectedKeys() 方法的执行时间作为基准，计算 #runAllTasks(long timeoutNanos) 方法可执行的时间
                        // Ensure we always run tasks.
                        // 运行所有普通任务和定时任务，限制时间
                        // 处理IO事件的时间
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // 计算用于处理非IO任务的时间-netty的异步task机制，定时任务的处理逻辑
                        // reactor 线程如果在此停留的时间过长，那么将积攒许多的IO事件无法处理，最终导致大量客户端请求阻塞，因此，默认情况下，netty将控制内部队列的执行时间
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                // 当发生异常时，调用 #handleLoopException(Throwable t) 方法，处理异常
                handleLoopException(t);
            }
            // EventLoop 优雅关闭
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * 处理 Channel 新增就绪的 IO 事件
     *
     * 当 selectedKeys 非空，意味着使用优化的 SelectedSelectionKeySetSelector，所以调用 #processSelectedKeysOptimized() 方法；否则，调用 #processSelectedKeysPlain() 方法。
     */
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            // 处理被优化过的 selectKey
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    // 在channel从selector上移除的时候，调用cancel函数将key取消
    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        // 移除的key到达 CLEANUP_INTERVA L默认256，设置needsToSelectAgain为true
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    /**
     * 基于 Java NIO 原生 Selecotr，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        // 如果 selectedKeys 是空的，则直接返回
        if (selectedKeys.isEmpty()) {
            return;
        }

        // 遍历 SelectionKey 迭代器
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            // 获得 SelectionKey 对象
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            // 从迭代器中移除
            i.remove();

            // 处理 SelectedKey
            if (a instanceof AbstractNioChannel) {
                // 当 attachment 是 Netty NIO Channel 时，调用 #processSelectedKey(SelectionKey k, AbstractNioChannel ch) 方法，处理一个 Channel 就绪的 IO 事件
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                // 当 attachment 是 Netty NioTask 时，调用 #processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) 方法，使用 NioTask 处理一个 Channel 的 IO 事件
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 遍历结束退出循环
            if (!i.hasNext()) {
                break;
            }

            // 是否需要再次 select
            if (needsToSelectAgain) {
                // 进行 selectNow 立即返回
                selectAgain();
                // 重置 selectedKeys 以及迭代器
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 基于 Netty SelectedSelectionKeySetSelector，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeysOptimized() {
        // 循环遍历 selectedKeys 数组
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 取出IO事件以及对应的netty channel
            final SelectionKey k = selectedKeys.keys[i];

            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // 假设一个NioEventLoop平均每次轮询出N个IO事件，高峰期轮询出3N个事件，那么selectedKeys的物理长度要大于等于3N，如果每次处理这些key，不置selectedKeys[i]为空，
            // 那么高峰期一过，这些保存在数组尾部的selectedKeys[i]对应的SelectionKey将一直无法被回收，SelectionKey对应的对象可能不大，
            // 但是要知道，它可是有attachment的，attachment可能很大，这样一来，这些元素是GC root可达的，很容易造成gc不掉，内存泄漏就发生了
            // 置空
            selectedKeys.keys[i] = null;

            // netty的doRegister()中将AbstractNioChannel内部的jdk类SelectableChannel对象注册到jdk类Selctor对象上去，
            // 并且将AbstractNioChannel作为SelectableChannel对象的一个attachment附属上，
            // 这样再jdk轮询出某条SelectableChannel有IO事件发生时，就可以直接取出AbstractNioChannel进行后续操作
            final Object a = k.attachment();

            // 当 attachment 是 Netty NIO Channel 时，调用 #processSelectedKey(SelectionKey k, AbstractNioChannel ch) 方法，处理一个 Channel 就绪的 IO 事件
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                // 当 attachment 是 Netty NioTask 时，调用 #processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) 方法，使用 NioTask 处理一个 Channel 的 IO 事件
                // 注册到selctor上的attachment还有另外一种类型，就是NioTask，NioTask主要是用于当一个 SelectableChannel注册到selector的时候，执行一些任务
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            // 判断是否该再来次轮询
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                // 将 selectedKeys 的内部数组全部清空，方便被 jvm 垃圾回收
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理一个 Channel 就绪的 IO 事件
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 拿到Unsafe对象(NioMessageUnsafe是服务端的channel操作对象、NioByteUnsafe是客户端的channel操作对象)
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();

        // 如果 SelectionKey 是不合法的，则关闭 Channel
        if (!k.isValid()) {
            // 如果 key 是失效的
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            // channel 已不在该 EventLoop，直接返回
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            // channel 还在 EventLoop，关闭 channel
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 获得就绪的 IO 事件的 ops，获取 selectionKey 关联的 Channel 所准备好了的操作 IO 事件并根据类型处理
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // OP_CONNECT 事件就绪
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) { // 客户端连接事件准备好了
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                // 移除对 OP_CONNECT 的感兴趣，即不再监听连接事件
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops); // 连接完成后客户端除了连接事件都感兴趣

                // AbstractNioUnsafe#finishConnect() 通过 Selector 轮询到 SelectionKey.OP_CONNECT 事件时，进行触发
                // 完成连接
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // OP_WRITE 事件就绪
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {// 写事件准备好了
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 向 Channel 写入数据，在完成写入数据后，会移除对 OP_WRITE 的感兴趣
                // 在写入到 Channel 到对端，若 TCP 数据发送缓冲区已满，这将导致 Channel 不写可，此时会注册对该 Channel 的 SelectionKey.OP_WRITE 事件感兴趣。
                // 从而实现，再在 Channel 可写后，进行强制 flush，向 Channel 写入数据
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // SelectionKey.OP_READ 或 SelectionKey.OP_ACCEPT 就绪
            // readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
            // 当 (readyOps & SelectionKey.OP_ACCEPT) != 0 时，这就是服务端 NioServerSocketChannel 的 boss EventLoop 线程轮询到有新的客户端连接接入
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {// readyOps == 0为对JDK Bug的处理，防止死循环
                // 处理读或者接受客户端连接的事件
                unsafe.read();// 读事件以及服务端的 Accept 事件都抽象为 read() 事件
            }
        } catch (CancelledKeyException ignored) {
            // 发生异常，关闭 Channel
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * 使用 NioTask，自定义实现 Channel 处理 Channel IO 就绪的事件
     */
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        // 未执行
        int state = 0;
        try {
            // 调用 NioTask 的 Channel 就绪事件
            task.channelReady(k.channel(), k);
            // 执行成功
            state = 1;
        } catch (Exception e) {
            // SelectionKey 取消
            k.cancel();
            // 执行 Channel 取消注册
            invokeChannelUnregistered(task, k, e);
            // 执行异常
            state = 2;
        } finally {
            switch (state) {
            case 0:
                // SelectionKey 取消
                k.cancel();
                // 执行 Channel 取消注册
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                // SelectionKey 不合法，则执行 Channel 取消注册
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    /**
     * 执行 Channel 取消注册
     */
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            // 调用 NioTask#channelUnregistered() 方法，执行 Channel 取消注册
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    /**
     * 唤醒线程
     */
    @Override
    protected void wakeup(boolean inEventLoop) {
        // 因为 Selector#wakeup() 方法的唤醒操作是开销比较大的操作，并且每次重复调用相当于重复唤醒。所以，通过 wakenUp 属性，通过 CAS 修改 false => true ，保证有且仅有进行一次唤醒
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            // 因为 NioEventLoop 的线程阻塞，主要是调用 Selector#select(long timeout) 方法，阻塞等待有 Channel 感兴趣的 IO 事件，或者超时。所以需要调用 Selector#wakeup() 方法，进行唤醒 Selector
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * 调用 Selector#selectorNow() 方法，立即 (无阻塞) 返回 Channel 新增的感兴趣的就绪 IO 事件数量
     */
    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            // 若唤醒标识 wakeup 为 true 时，调用 Selector#wakeup() 方法，唤醒 Selector。
            // 如果有其它线程调用了 #wakeup() 方法，但当前没有线程阻塞在 #select() 方法上，下个调用 #select() 方法的线程会立即被唤醒
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    /**
     * 选择 (查询) 任务
     */
    private void select(boolean oldWakenUp) throws IOException {
        // 记录下 Selector 对象，不需要每次访问使用 volatile 修饰的 selector 属性
        Selector selector = this.selector;
        try {
            // 获得 select 操作的计数器，主要用于记录 Selector 空轮询次数，所以每次在正在轮询完成(例如：轮询超时)，则重置 selectCnt 为 1
            int selectCnt = 0;
            // 记录当前时间，单位：纳秒
            long currentTimeNanos = System.nanoTime();

            // 延迟任务队列中第一个任务的截止时间点
            // netty 里面定时任务队列是按照延迟时间从小到大进行排序
            // delayNanos(currentTimeNanos) 方法即取出第一个定时任务的延迟时间
            // 计算 select 操作的截止时间，单位：纳秒。delayNanos(currentTimeNanos) 方法返回的为下一个定时任务距离现在的时间，如果不存在定时任务，则默认返回 1000 ms。
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            // 死"循环"，直到符合如下任一一种情况后结束: 1、select 操作超时 2、若有新的任务加入 3、查询到任务或者唤醒 4、线程被异常打断 5、发生 NIO 空轮询的 Bug 后重建 Selector 对象后
            for (;;) {
                // 计算本次 select 的超时时长，单位：毫秒。加 500000L 是为了四舍五入，除 1000000L 是为了纳秒转为毫秒，因为 Selector#select(timeoutMillis) 方法，可能因为各种情况结束，所以需要循环，并且每次重新计算超时时间
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                // 如果超时时长小于 0，则结束 select，定时任务截止时间快到了，中断本次轮询
                if (timeoutMillis <= 0) {
                    // 如果是首次 select，则调用 Selector#selectNow() 方法，获得非阻塞的 Channel 感兴趣的就绪的 IO 事件，并重置 selectCnt 为 1
                    // 跳出之前如果发现目前为止还没有进行过 select 操作就调用一次 selectNow()
                    if (selectCnt == 0) {
                        // 该方法会立即返回，不会阻塞
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                // netty 为了保证任务队列能够及时执行，在进行阻塞 select 操作的时候会判断任务队列是否为空，如果不为空，就执行一次非阻塞 select 操作，跳出循环
                // 若有新的任务加入，轮询过程中发现有新的任务加入，中断本次轮询
                // 第一种，提交的任务的类型是 NonWakeupRunnable，那么它并不会调用 #wakeup() 方法，Netty 在 #select() 方法的设计上，希望能尽快执行任务。此时如果标记 wakeup 为 false，说明符合这种情况，直接结束 select。
                // 第二种，提交的任务的类型不是 NonWakeupRunnable，那么在 #run() 方法的 wakenUp.getAndSet(false) 之前，发起了一次 #wakeup() 方法，那么因为 wakenUp.getAndSet(false) 会将标记 wakeUp 设置为 false，所以就能满足条件
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    // selectNow 一次，非阻塞的获取一次 Channel 新增的就绪的 IO 事件
                    selector.selectNow();
                    // 重置 select 计数器
                    selectCnt = 1;
                    break;
                }
                // 执行阻塞式 select 操作，查询 Channel 是否有就绪的 IO 事件，截止到第一个定时任务的截止时间
                // 执行到这一步，说明 netty 任务队列里面队列为空，并且所有定时任务延迟时间还未到(大于0.5ms)，于是，在这里进行一次阻塞 select 操作，截止到第一个定时任务的截止时间
                // 如果第一个定时任务的延迟非常长，那么有可能线程一直阻塞在 select 操作，但是只要在这段时间内，有新任务加入，该阻塞就会被释放，外部线程调用 execute 方法添加任务，会调用 wakeup 方法唤醒 selector 阻塞
                int selectedKeys = selector.select(timeoutMillis);
                // select 计数器 ++
                selectCnt ++;

                // 结束 select，如果满足下面任一一个条件，则中断本次轮询
                // selectedKeys != 0 时，表示有 Channel 新增的就绪的 IO 事件，所以结束 select
                // oldWakenUp || wakenUp.get() 时，表示 Selector 被唤醒，所以结束 select
                // hasTasks() || hasScheduledTasks()，表示任务队列里面有普通任务或定时任务，所以结束 select
                // 那么剩余的情况，主要是 select 超时或者发生空轮询

                // 轮询到IO事件 （selectedKeys != 0）
                // oldWakenUp 参数为true
                // 任务队列里面有任务（hasTasks）
                // 第一个定时任务即将要被执行 （hasScheduledTasks（））
                // 用户主动唤醒（wakenUp.get()）
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }

                // 线程被打断。一般情况下不会出现，出现基本是 bug，或者错误使用。
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                // 解决 jdk 的 nio bug，该 bug 会导致 Selector 一直空轮询，最终导致 cpu 100%，nio server 不可用
                // 如果持续的时间大于等于timeoutMillis，说明就是一次有效的轮询，重置 selectCnt 标志，
                // 否则，表明该阻塞方法并没有阻塞这么长时间，可能触发了 jdk 的空轮询 bug，当空轮询的次数超过一个阀值的时候，默认是 512，就开始重建 selector
                // 记录当前时间
                long time = System.nanoTime();

                // 若满足 time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos，说明到达此处时，Selector 是超时 select，那么是正常的，所以重置 selectCnt 为 1
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // 不符合 select 超时的提交，可能触发了 jdk 的空轮询 bug，若 select 空轮询的次数到达重建 Selector 对象的上限，则进行重建。
                    // 这就是 Netty 判断发生 NIO Selector 空轮询的方式，N (默认 512) 次 select 并未阻塞超时这么长，那么就认为发生 NIO Selector 空轮询。过多的 NIO Selector 将会导致 CPU 100%
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    // 重建 Selector 对象
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                // 记录新的当前时间，用于重新计算本次 select 的超时时长
                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);
        // 重建 Selector 对象
        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        // 立即 selectNow 一次，非阻塞
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        // 每次在抓到IO事件之后，都会将 needsToSelectAgain 重置为false
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
