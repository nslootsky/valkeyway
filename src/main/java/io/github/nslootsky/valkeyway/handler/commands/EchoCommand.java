/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * ECHO message: answered by the proxy itself.
 */
public class EchoCommand implements CommandHandler {

    @Override
    public Reply execute(Request request) {
        return request.argCount() == 1
                ? new Reply.Bulk(request.arg(0))
                : Reply.error("ERR wrong number of arguments for 'echo' command");
    }
}
