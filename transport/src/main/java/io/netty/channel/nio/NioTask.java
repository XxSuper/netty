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

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * An arbitrary task that can be executed by {@link NioEventLoop} when a {@link SelectableChannel} becomes ready.
 *
 * @see NioEventLoop#register(SelectableChannel, int, NioTask)
 *
 * 用于自定义 Nio 事件处理接口，对于每个 Nio 事件，可以认为是一个任务 (Task)，NioTask 在 Netty 自身中并未有相关的实现类
 */
public interface NioTask<C extends SelectableChannel> {
    /**
     * Invoked when the {@link SelectableChannel} has been selected by the {@link Selector}.
     *
     * 处理 Channel IO 就绪的事件，相当于说，我们可以通过实现该接口方法，实现 processSelectedKey 的逻辑
     */
    void channelReady(C ch, SelectionKey key) throws Exception;

    /**
     * Invoked when the {@link SelectionKey} of the specified {@link SelectableChannel} has been cancelled and thus
     * this {@link NioTask} will not be notified anymore.
     *
     * @param cause the cause of the unregistration. {@code null} if a user called {@link SelectionKey#cancel()} or
     *              the event loop has been shut down.
     *
     * Channel 取消注册。一般来说，我们可以通过实现该接口方法，关闭 Channel
     */
    void channelUnregistered(C ch, Throwable cause) throws Exception;
}
