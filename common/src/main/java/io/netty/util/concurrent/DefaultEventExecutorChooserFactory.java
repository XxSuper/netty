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

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 *
 * 实现 EventExecutorChooserFactory 接口，默认 EventExecutorChooser 工厂实现类
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    /**
     * 单例
     */
    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        // 判断 EventExecutor 数组的大小是否为 2 的幂次方
        if (isPowerOfTwo(executors.length)) {
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            return new GenericEventExecutorChooser(executors);
        }
    }

    /**
     * 为什么 (val & -val) == val 可以判断数字是否为 2 的幂次方呢？
     * 我们以 8 来举个例子。8 的二进制为 1000，-8 的二进制使用补码表示。所以，先求反生成反码为 0111，然后加 1 生成补码为 1000。8 和 -8 并操作后，还是 8。
     * 实际上，以 2 为幂次方的数字，都是最高位为 1，剩余位为 0，所以对应的负数，求完补码还是自己。
     *
     * @param val
     * @return
     */
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    /**
     * PowerOfTwoEventExecutorChooser 实现 EventExecutorChooser 接口，基于 EventExecutor 数组的大小为 2 的幂次方的 EventExecutor 选择器实现类。
     */
    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {

        /**
         * 自增序列
         */
        private final AtomicInteger idx = new AtomicInteger();

        /**
         * EventExecutor 数组
         */
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * 通过 idx 自增，并使用【EventExecutor 数组的大小 - 1】进行 & 并操作，- (二元操作符) 的计算优先级高于 & (一元操作符)
         * EventExecutor 数组的大小是以 2 为幂次方的数字，那么减一后，除了最高位是 0 ，剩余位都为 1 (例如 8 减一后等于 7，而 7 的二进制为 0111。)，
         * 那么无论 idx 如何递增，再进行 & 并操作，都不会超过 EventExecutor 数组的大小。并且，还能保证顺序递增。
         *
         * @return
         */
        @Override
        public EventExecutor next() {
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    /**
     * GenericEventExecutorChooser 实现 EventExecutorChooser 接口，通用的 EventExecutor 选择器实现类
     */
    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        /**
         * 自增序列
         */
        private final AtomicInteger idx = new AtomicInteger();

        /**
         * EventExecutor 数组
         */
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * 使用 idx 自增，并使用 EventExecutor 数组的大小来取余，获取 EventExecutor 数组下标
         *
         * @return
         */
        @Override
        public EventExecutor next() {
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
