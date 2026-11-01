/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.InlineCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * PING [message]: answered by the proxy itself.
 */
public class PingCommand implements InlineCommandHandler {

    @Override
    public Reply execute(Request request) {
        return switch (request.argCount()) {
            case 0 -> Reply.PONG;
            case 1 -> new Reply.Bulk(request.arg(0));
            default -> Reply.error("ERR wrong number of arguments for 'ping' command");
        };
    }
}
