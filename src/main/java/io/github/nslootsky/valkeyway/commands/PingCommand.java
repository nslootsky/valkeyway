/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.InlineCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * PING [message]: answered by the proxy itself. A RESP2 client with subscriptions gets
 * {@code ["pong", message]}, as from a server. [upstream:V8]
 */
public class PingCommand implements InlineCommandHandler {

    private static final Reply PONG = Reply.bulk("pong");

    @Override
    public boolean allowedWhileSubscribed() {
        return true;
    }

    @Override
    public Reply execute(Request request) {
        if (request.connection().subscribed() && request.connection().protocol() == 2 && request.argCount() <= 1) {
            return Reply.array(PONG, request.argCount() == 0 ? Reply.bulk("") : new Reply.Bulk(request.arg(0)));
        }
        return switch (request.argCount()) {
            case 0 -> Reply.PONG;
            case 1 -> new Reply.Bulk(request.arg(0));
            default -> Reply.error("ERR wrong number of arguments for 'ping' command");
        };
    }
}
