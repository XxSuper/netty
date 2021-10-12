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
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 * 实现 OrderedEventExecutor 接口，继承 AbstractScheduledEventExecutor 抽象类，基于单线程的 EventExecutor 抽象类，即一个 EventExecutor 对应一个线程
 *
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    private static final int ST_NOT_STARTED = 1;
    private static final int ST_STARTED = 2;
    private static final int ST_SHUTTING_DOWN = 3;
    private static final int ST_SHUTDOWN = 4;
    private static final int ST_TERMINATED = 5;

    private static final Runnable WAKEUP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };
    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    /**
     * {@link #state} 字段的原子更新器
     */
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");

    /**
     * {@link #threadProperties} 字段的原子更新器
     */
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    /**
     * 任务队列
     *
     * @see #newTaskQueue(int)
     */
    private final Queue<Runnable> taskQueue;

    /**
     * 线程，在 SingleThreadEventExecutor 中，任务是提交到 taskQueue 队列中，而执行在 thread 线程中
     */
    private volatile Thread thread;

    /**
     * 线程属性
     */
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;

    /**
     * 执行器。通过它创建 thread 线程
     */
    private final Executor executor;

    /**
     * 线程是否已经打断
     *
     * @see #interruptThread()
     */
    private volatile boolean interrupted;

    private final Semaphore threadLock = new Semaphore(0);
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();

    /**
     * 添加任务到 taskQueue 队列时，是否唤醒线程{@link #thread}
     */
    private final boolean addTaskWakesUp;

    /**
     * 最大等待执行任务数量，即 {@link #taskQueue} 的队列大小
     */
    private final int maxPendingTasks;

    /**
     * 拒绝执行处理器，在 taskQueue 队列超过最大任务数量时，怎么拒绝处理新提交的任务
     *
     * @see #reject()
     * @see #reject(Runnable)
     */
    private final RejectedExecutionHandler rejectedExecutionHandler;

    /**
     * 最后执行时间
     */
    private long lastExecutionTime;

    /**
     * 状态，SingleThreadEventExecutor 在实现上，thread 的初始化采用延迟启动的方式，只有在第一个任务时，executor 才会执行并创建该线程，从而节省资源。
     *
     * thread 线程有 5 种状态：ST_NOT_STARTED（未开始）、ST_STARTED（已开始）、ST_SHUTTING_DOWN（正在关闭中）、ST_SHUTDOWN（已关闭）、ST_TERMINATED（已经终止）
     */
    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;

    private volatile long gracefulShutdownQuietPeriod;

    /**
     * 优雅关闭超时时间，单位：毫秒
     */
    private volatile long gracefulShutdownTimeout;

    /**
     * 优雅关闭开始时间，单位：毫秒
     */
    private long gracefulShutdownStartTime;

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ObjectUtil.checkNotNull(executor, "executor");
        // 创建任务队列
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     *
     * 默认返回的是 LinkedBlockingQueue 阻塞队列，如果子类有更好的队列选择(例如非阻塞队列)，可以重写该方法， NioEventLoop 就重写了这个方法
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // LinkedBlockingQueue是一个阻塞的线程安全的队列，底层采用链表实现
        // 1、add方法在添加元素的时候，若超出了队列的长度会直接抛出异常
        // 2、put方法，若向队尾添加元素的时候发现队列已经满了会发生阻塞一直等待空间，以加入元素
        // 3、offer方法在添加元素时，如果发现队列已满无法添加的话，会直接返回false
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * Interrupt the current running {@link Thread}.
     *
     * 打断 EventLoop 的线程，因为 EventLoop 的线程是延迟启动，所以可能 thread 并未创建，此时通过 interrupted 标记打断。之后在 #startThread() 方法中，创建完线程后，再进行打断，也就是说，“延迟打断”。
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        // 线程不存在，则标记线程被打断
        if (currentThread == null) {
            interrupted = true;
        } else {
            // 打断线程
            currentThread.interrupt();
        }
    }

    /**
     * @see Queue#poll()
     *
     * 获得队头的任务
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        // 因为获得的任务可能是 WAKEUP_TASK，所以需要通过循环来跳过
        for (;;) {
            // 获得并移除队首元素。如果获得不到，返回 null。注意，这个操作是非阻塞的
            Runnable task = taskQueue.poll();
            // 忽略 WAKEUP_TASK 任务，因为是空任务
            if (task == WAKEUP_TASK) {
                continue;
            }
            return task;
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 将定时任务队列 scheduledTaskQueue 到达可执行时间的任务，添加到任务队列 taskQueue 中。通过这样的方式，定时任务得以被执行
     */
    private boolean fetchFromScheduledTaskQueue() {
        // 获取当前时间到开始时间的时间间隔
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        // 获得指定时间内，定时任务队列首个可执行的任务，并且从队列中移除。
        Runnable scheduledTask  = pollScheduledTask(nanoTime);
        // 不断从定时任务队列中，获得
        while (scheduledTask != null) {
            // 将定时任务添加到 taskQueue 中。若添加失败，则结束循环，返回 false，表示未获取完所有可执行的定时任务
            // 将从定时任务队列中取出的到期的定时任务添加到 mpsc queue-普通任务队列里面
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                // 添加失败重新放入定时任务队列，将定时任务添加回 scheduledTaskQueue 中
                scheduledTaskQueue().add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
            // 获得指定时间内，定时任务队列首个可执行的任务，并且从队列中移除。
            scheduledTask  = pollScheduledTask(nanoTime);
        }
        // 返回 true，表示获取完所有可执行的定时任务
        return true;
    }

    /**
     * @see Queue#peek()
     *
     * 返回队头的任务，但是不移除
     */
    protected Runnable peekTask() {
        // 仅允许在 EventLoop 线程中执行
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     *
     * 队列中是否有任务
     */
    protected boolean hasTasks() {
        // 仅允许在 EventLoop 线程中执行
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it with care!</strong>
     *
     * 获得队列中的任务数
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     *
     * 在 #offerTask(Runnable task) 的方法的基础上，若添加任务到队列中失败，则进行拒绝任务
     */
    protected void addTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (!offerTask(task)) {
            // 调用 #reject(task) 方法，拒绝任务
            reject(task);
        }
    }

    /**
     * 添加任务到队列中。若添加失败，则返回 false
     */
    final boolean offerTask(Runnable task) {
        // 关闭时，拒绝任务
        if (isShutdown()) {
            reject();
        }
        // 添加任务到队列，对于 BlockingQueue 的 #offer(E e) 方法，也不是阻塞的
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     *
     * 移除指定任务
     */
    protected boolean removeTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        return taskQueue.remove(task);
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     *
     * 执行所有任务直到所有任务执行完成
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        // 标记是否执行过任务
        boolean ranAtLeastOne = false;

        do {
            // 将定时任务队列 scheduledTaskQueue 到达可执行时间的任务，添加到任务队列 taskQueue 中。但是实际上，任务队列 taskQueue 是有队列大小上限的，因此使用 while 循环，直到没有到达可执行时间的任务为止
            fetchedAll = fetchFromScheduledTaskQueue();
            // 执行任务队列中的所有任务
            if (runAllTasksFrom(taskQueue)) {
                // 若有任务执行，则标记为 true
                ranAtLeastOne = true;
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        // 如果执行过任务，则设置最后执行时间
        if (ranAtLeastOne) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        // 执行所有任务完成的后续方法
        afterRunningAllTasks();
        // 返回是否执行过任务
        return ranAtLeastOne;
    }

    /**
     * Runs all tasks from the passed {@code taskQueue}.
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed.
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        // 获得队头的任务
        Runnable task = pollTaskFrom(taskQueue);
        // 获取不到，结束执行，返回 false
        if (task == null) {
            return false;
        }
        for (;;) {
            // 执行任务
            safeExecute(task);
            // 获得队头的任务
            task = pollTaskFrom(taskQueue);
            // 获取不到，结束执行，返回 true
            if (task == null) {
                return true;
            }
        }
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     *
     * 执行所有任务直到完成所有任务，或者超过执行时间上限。方法的返回值，表示是否执行过任务。因为，任务队列可能为空，那么就会返回 false，表示没有执行过任务。
     */
    protected boolean runAllTasks(long timeoutNanos) {
        // 获得到时间的定时任务，将到期的定时任务转移到 mpsc queue 里面
        fetchFromScheduledTaskQueue();
        // 首次调用 #pollTask() 方法，获得队头的任务。从 taskQueue 中获取任务
        Runnable task = pollTask();
        // 获取不到，结束执行，并返回 false
        if (task == null) {
            // NioEventLoop 可以通过父类 SingleTheadEventLoop 的 executeAfterEventLoopIteration 方法向 tailTasks 中添加收尾任务
            // 执行所有任务完成的后续方法
            afterRunningAllTasks();
            return false;
        }
        // 计算执行任务的截止时间，用reactor线程传入的超时时间 timeoutNanos 来计算出当前任务循环的deadline（截止时间）
        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        // 执行任务计数
        long runTasks = 0;
        long lastExecutionTime;
        // 循环执行任务
        for (;;) {
            // 安全的执行任务
            safeExecute(task);
            // 已执行任务的个数 + 1
            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            // 每执行完64个任务之后，判断当前时间是否超过本次reactor任务循环的截止时间了，如果超过，那就break掉，如果没有超过，那就继续执行。
            // netty对性能的优化考虑地相当的周到，假设netty任务队列里面如果有海量小任务，如果每次都要执行完任务都要判断一下是否到截止时间，那么效率是比较低下的
            // 每隔 64 个任务检查一次时间，因为 nanoTime() 是相对费时的操作，也因此，超过执行时间上限是“近似的”，而不是绝对准确，64 这个值当前是硬编码的，无法配置，可能会成为一个问题。
            if ((runTasks & 0x3F) == 0) {
                // 获取当前的时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                // 超过任务截止时间，结束执行
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            // 获得队头的任务
            task = pollTask();
            // 获取不到，结束执行
            if (task == null) {
                // 获取当前的时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }
        // NioEventLoop 可以通过父类 SingleTheadEventLoop 的 executeAfterEventLoopIteration 方法向 tailTasks 中添加收尾任务
        // 执行所有任务完成的后续方法
        afterRunningAllTasks();
        // 设置最后执行时间
        this.lastExecutionTime = lastExecutionTime;
        // 返回 true，表示有执行任务
        return true;
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     *
     * 执行所有任务完成的后续方法
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }
    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     * netty 里面定时任务队列是按照延迟时间从小到大进行排序的，计算延迟任务队列中第一个任务的到期执行时间（即最晚还能延迟多长时间执行），默认返回 1s
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #nanoTime()}) at which the the next
     * closest scheduled task should run.
     */
    @UnstableApi
    protected long deadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * 抽象方法，由子类实现，如何执行 taskQueue 队列中的任务
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     * <p>
     * 清理释放资源。该方法为空方法。在子类 NioEventLoop 中，我们会看到它覆写该方法，关闭 NIO Selector 对象
     */
    protected void cleanup() {
        // NOOP
    }

    /**
     * 唤醒线程
     */
    protected void wakeup(boolean inEventLoop) {
        // 判断不在 EventLoop 的线程中。因为，如果在 EventLoop 线程中，意味着线程就在执行中，不必要唤醒。
        if (!inEventLoop || state == ST_SHUTTING_DOWN) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            // 调用 Queue#offer(E e) 方法，添加任务到队列中。而添加的任务是 WAKEUP_TASK，这是一个空的 Runnable 实现类。仅仅用于唤醒基于 taskQueue 阻塞拉取的 EventLoop 实现类
            // NioEventLoop 会重写该方法，通过 NIO Selector 唤醒
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    /**
     * 判断指定线程是否是 EventLoop 线程
     */
    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        if (quietPeriod < 0) {
            throw new IllegalArgumentException("quietPeriod: " + quietPeriod + " (expected >= 0)");
        }
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (isShuttingDown()) {
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    /**
     * 优雅关闭
     */
    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            wakeup(true);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            wakeup(true);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        if (threadLock.tryAcquire(timeout, unit)) {
            threadLock.release();
        }

        return isTerminated();
    }

    /**
     * 执行一个任务
     * 外部线程在往任务队列里面添加任务的时候执行 startThread()，netty 会判断 reactor 线程有没有被启动，如果没有被启动，那就启动线程
     */
    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        // 首先判断该 EventLoop 的线程是否是当前线程
        boolean inEventLoop = inEventLoop();
        // 向任务队列里面添加任务
        addTask(task);
        // 非 EventLoop 的线程
        if (!inEventLoop) {
            // 创建线程，启动 EventLoop 独占的线程，即 thread 属性
            startThread();
            // 若已经关闭，则移除任务，并进行拒绝
            if (isShutdown()) {
                boolean reject = false;
                try {
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                if (reject) {
                    reject();
                }
            }
        }
        // addTaskWakesUp:调用 addTask(Runnable) 添加任务时是否能唤醒线程。wakesUpForTask(task) 方法，判断该任务是否需要唤醒线程
        // 由于 DefaultEventExecutor 是通过 BlockingQueue 的阻塞来实现唤醒的，所以 addTaskWakesUp=true
        if (!addTaskWakesUp && wakesUpForTask(task)) {
            // 唤醒线程
            wakeup(inEventLoop);
        }
    }

    /**
     * 在 EventExecutor 中执行多个普通任务，有一个执行完成即可
     */
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        // 调用 #throwIfInEventLoop(String method) 方法，判断若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常。
        throwIfInEventLoop("invokeAny");
        // 调用父类 AbstractScheduledEventExecutor 的 #invokeAny(tasks, ...) 方法，执行多个普通任务，有一个执行完成即可。在该方法内部，会调用 #execute(Runnable task) 方法，执行任务
        return super.invokeAny(tasks);
    }

    /**
     * 在 EventExecutor 中执行多个普通任务，有一个执行完成即可
     */
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    /**
     * 在 EventExecutor 中执行多个普通任务
     */
    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    /**
     * 在 EventExecutor 中执行多个普通任务
     */
    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        // 调用 #throwIfInEventLoop(String method) 方法，判断若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常
        throwIfInEventLoop("invokeAll");
        // 调用父类 AbstractScheduledEventExecutor 的 #invokeAll(tasks, ...) 方法，执行多个普通任务，在该方法内部，会调用 #execute(Runnable task) 方法，执行任务
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     *
     * 获得 EventLoop 的线程属性，为什么 #threadProperties() 方法不直接返回 thread 呢？因为如果直接返回 thread，调用方可以调用到该变量的其他方法，这个是我们不希望看到的
     */
    public final ThreadProperties threadProperties() {
        // 获得 ThreadProperties 对象。若不存在，则进行创建 ThreadProperties 对象。
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            // 获得 EventLoop 的线程。因为线程是延迟启动的，所以会出现线程为空的情况。若线程为空，则需要进行创建
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                // 调用 #submit(Runnable) 方法，提交任务，就能促使 #execute(Runnable) 方法执行创建 EventLoop 的线程（EventLoop 线程是在第一次提交任务时创建的），调用 Future#syncUninterruptibly() 方法，保证 execute() 方法中异步创建 thread 完成
                submit(NOOP_TASK).syncUninterruptibly();
                // 获得线程，并断言保证线程存在。
                thread = this.thread;
                assert thread != null;
            }

            // 创建 DefaultThreadProperties 对象
            threadProperties = new DefaultThreadProperties(thread);
            // CAS 修改 threadProperties 属性
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    @SuppressWarnings("unused")
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    /**
     * 拒绝任何任务，用于 SingleThreadEventExecutor 已关闭( #isShutdown() 方法返回的结果为 true)的情况
     */
    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * Offers the task to the associated {@link RejectedExecutionHandler}. 拒绝任务，调用 RejectedExecutionHandler#rejected(Runnable task, SingleThreadEventExecutor executor) 方法，拒绝该任务
     *
     * @param task to reject.
     */
    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    /**
     * 启动 EventLoop 独占的线程，即 thread 属性
     */
    private void startThread() {
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                try {
                    doStartThread();
                } catch (Throwable cause) {
                    STATE_UPDATER.set(this, ST_NOT_STARTED);
                    PlatformDependent.throwException(cause);
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 调用内部执行器 executor 的 execute 方法，将调用 NioEventLoop 的 run 方法的过程封装成一个 runnable 塞到一个线程中去执行，
     * 该线程就是 executor 创建，对应 netty 的 reactor 线程实体。executor 默认是 ThreadPerTaskExecutor。
     * 默认情况下，ThreadPerTaskExecutor 在每次执行 execute 方法的时候都会通过 DefaultThreadFactory 创建一个 FastThreadLocalThread 线程，而这个线程就是 netty 中的 reactor 线程实体
     */
    private void doStartThread() {
        // 断言，保证 thread 为空
        assert thread == null;
        // executor 默认是 ThreadPerTaskExecutor 和 DefaultThreadFactory，线程实体是 FastThreadLocalThread，创建 EventLoopGroup 时确定的
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 执行以后将当前线程赋值给 EventLoop。赋值当前的线程给 thread 属性，这就是，每个 SingleThreadEventExecutor 独占的线程的创建方式
                thread = Thread.currentThread();

                // 如果当前线程已经被标记打断，则进行打断操作
                if (interrupted) {
                    thread.interrupt();
                }

                // 是否执行成功
                boolean success = false;
                // 更新内部时间戳，该时间戳指示最近执行提交的任务的时间。
                updateLastExecutionTime();
                try {
                    // 执行任务
                    SingleThreadEventExecutor.this.run();
                    // 标记执行成功
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    for (;;) {
                        int oldState = state;
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }

                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks.
                        for (;;) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }
                    } finally {
                        try {
                            // 清理，释放资源
                            cleanup();
                        } finally {
                            // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                            // the future. The user may block on the future and once it unblocks the JVM may terminate
                            // and start unloading classes.
                            // See https://github.com/netty/netty/issues/6596.
                            FastThreadLocal.removeAll();

                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.release();
                            if (!taskQueue.isEmpty()) {
                                if (logger.isWarnEnabled()) {
                                    logger.warn("An event executor terminated with " +
                                            "non-empty task queue (" + taskQueue.size() + ')');
                                }
                            }
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    /**
     * DefaultThreadProperties 实现 ThreadProperties 接口，默认线程属性实现类
     *
     * 每个实现方法，实际上就是对被包装的线程 t 的方法的封装
     */
    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
