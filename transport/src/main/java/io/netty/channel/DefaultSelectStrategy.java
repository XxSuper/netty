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
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 *
 * DefaultSelectStrategy，实现 SelectStrategy 接口，默认选择策略实现类
 */
final class DefaultSelectStrategy implements SelectStrategy {

    /**
     * 单例
     */
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    /**
     * 当 hasTasks 为 true，表示当前已经有任务，所以调用 IntSupplier#get() 方法，返回当前 Channel 新增的 IO 就绪事件的数量，
     * 当 hasTasks 为 false 时，直接返回 SelectStrategy.SELECT，进行阻塞 select Channel 感兴趣的就绪 IO 事件
     */
    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        // 如果还有task待执行则先执行selectNow，selectNow是立即返回的，不是阻塞等待
        // 如果没有待执行的task则执行select，有可能是阻塞等待IO事件
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
