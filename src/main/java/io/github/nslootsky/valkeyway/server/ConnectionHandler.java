/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.ProtocolException;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Per-channel glue between Netty and a {@link Connection}: hands decoded commands to it, applies
 * the client limits, and turns protocol errors into Valkey-style error-and-close.
 */
final class ConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private final ServerContext server;
    private Connection connection;

    ConnectionHandler(ServerContext server) {
        this.server = server;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connection = server.register(ctx.channel());
        if (connection == null) {
            ctx.writeAndFlush(Reply.error("ERR max number of clients reached")).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (connection != null && msg instanceof byte[][] command) {
            connection.enqueue(command);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (connection != null) {
            connection.updateReading();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent && connection != null && !connection.isBusy()) {
            log.debug("Closing idle client {}", connection.id());
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (connection != null) {
            connection.markClosed();
            server.unregister(connection);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof DecoderException && cause.getCause() instanceof ProtocolException protocol) {
            log.debug("Protocol error from client: {}", protocol.getMessage());
            if (connection != null) {
                connection.markClosed();
            }
            ctx.writeAndFlush(Reply.error(protocol.reply())).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (cause instanceof IOException) {
            log.debug("Client connection error: {}", cause.getMessage());
        } else {
            log.warn("Unexpected error on client connection", cause);
        }
        ctx.close();
    }
}
