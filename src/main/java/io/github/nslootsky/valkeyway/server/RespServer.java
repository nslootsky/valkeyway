/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.ReplyEncoder;
import io.github.nslootsky.valkeyway.protocol.RespDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The client-facing RESP server: Netty event loops for network I/O, and a virtual thread per
 * busy connection for running commands.
 */
public final class RespServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RespServer.class);

    /**
     * Server settings; see {@code ValkeywayProperties}.
     *
     * @param port    plain-text port, 0 for any free port, or -1 for none
     * @param tlsPort TLS port, 0 for any free port, or -1 for none
     * @param tls     TLS settings, required with a TLS port
     * @param authRequired whether connections must AUTH first
     */
    public record Settings(String host, int port, int tlsPort, TlsContext tls, int ioThreads, int maxClients,
                           long idleTimeoutSeconds, boolean authRequired) {
        public Settings {
            if (port < 0 && tlsPort < 0) {
                throw new IllegalArgumentException("Neither a port nor a TLS port to listen on");
            }
            if (tlsPort >= 0 && tls == null) {
                throw new IllegalArgumentException("A TLS port needs TLS settings");
            }
        }

        /** Plain text only, without passwords (tests). */
        public Settings(String host, int port, int ioThreads, int maxClients, long idleTimeoutSeconds) {
            this(host, port, -1, null, ioThreads, maxClients, idleTimeoutSeconds, false);
        }
    }

    /** How often the TLS files are checked for a rotated certificate. */
    static final long TLS_RELOAD_SECONDS = 60;

    private final Settings settings;
    private final ExecutorService executor;
    private final ServerContext context;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Channel tlsChannel;

    public RespServer(Settings settings, CommandTable commands) {
        this.settings = settings;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("client-", 0).factory());
        this.context = new ServerContext(commands, executor, settings.maxClients(), settings.authRequired());
    }

    public void start() throws InterruptedException {
        boolean epoll = Epoll.isAvailable();
        IoHandlerFactory io = epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        Class<? extends ServerChannel> channelType = epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
        bossGroup = new MultiThreadIoEventLoopGroup(1, io);
        workerGroup = new MultiThreadIoEventLoopGroup(settings.ioThreads(), io);

        if (settings.port() >= 0) {
            serverChannel = bootstrap(channelType, false).bind(settings.host(), settings.port()).sync().channel();
            log.info("Listening on {} ({} transport, {} I/O threads)", serverChannel.localAddress(),
                    epoll ? "epoll" : "nio", settings.ioThreads());
        }
        if (settings.tlsPort() >= 0) {
            tlsChannel = bootstrap(channelType, true).bind(settings.host(), settings.tlsPort()).sync().channel();
            log.info("Listening for TLS on {}", tlsChannel.localAddress());
            bossGroup.next().scheduleWithFixedDelay(settings.tls()::reloadIfChanged,
                    TLS_RELOAD_SECONDS, TLS_RELOAD_SECONDS, TimeUnit.SECONDS);
        }
    }

    private ServerBootstrap bootstrap(Class<? extends ServerChannel> channelType, boolean tls) {
        return new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(channelType)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(256 * 1024, 1024 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (tls) {
                            ch.pipeline().addLast(settings.tls().current().newHandler(ch.alloc()));
                        }
                        if (settings.idleTimeoutSeconds() > 0) {
                            ch.pipeline().addLast(new IdleStateHandler(0, 0, settings.idleTimeoutSeconds(), TimeUnit.SECONDS));
                        }
                        ch.pipeline()
                                .addLast(ReplyEncoder.INSTANCE)
                                .addLast(new RespDecoder(settings.authRequired()))
                                .addLast(new ConnectionHandler(context));
                    }
                });
    }

    /** The bound plain-text port. */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** The bound TLS port. */
    public int tlsPort() {
        return ((InetSocketAddress) tlsChannel.localAddress()).getPort();
    }

    Collection<Connection> clients() {
        return context.clients();
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (tlsChannel != null) {
            tlsChannel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        executor.shutdownNow();
    }
}
