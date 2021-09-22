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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        // boss 线程组：用于服务端接受客户端的连接
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // worker 线程组：用于进行客户端的 SocketChannel 的数据读写
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        // 创建 io.netty.example.echo.EchoServerHandler 处理器对象
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            // 创建 ServerBootstrap 对象，用于设置服务端的启动配置
            ServerBootstrap b = new ServerBootstrap();
            // 设置使用的 EventLoopGroup
            b.group(bossGroup, workerGroup)
             // 设置要被实例化的 Channel 为 NioServerSocketChannel 类
             .channel(NioServerSocketChannel.class)
             // 设置 NioServerSocketChannel 的可选项。在 io.netty.channel.ChannelOption 类中，枚举了相关的可选项
             .option(ChannelOption.SO_BACKLOG, 100)
             // 设置 NioServerSocketChannel 的处理器，使用了 io.netty.handler.logging.LoggingHandler 类，用于打印服务端的每个事件
             .handler(new LoggingHandler(LogLevel.INFO))
             // 设置连入服务端的 Client 的 SocketChannel 的处理器，使用 ChannelInitializer 来初始化连入服务端的 Client 的 SocketChannel 的处理器。
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });

            // Start the server.
            // 先调用 #bind(int port) 方法，绑定端口，后调用 ChannelFuture#sync() 方法，阻塞等待成功。
            ChannelFuture f = b.bind(PORT).addListener(new ChannelFutureListener() { // <1> 监听器就是我！
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    System.out.println("异常：" + future.cause());
                }
            }).sync();

            // Wait until the server socket is closed.
            // 先调用 #closeFuture() 方法，监听服务器关闭，后调用 ChannelFuture#sync() 方法，阻塞等待成功。此处不是关闭服务器，而是“监听”关闭
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            // 执行到此处，说明服务端已经关闭
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
