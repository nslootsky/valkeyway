/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * QUIT: replies OK, then closes the connection.
 */
public class QuitCommand implements CommandHandler {

    @Override
    public Reply execute(Request request) {
        request.connection().closeAfterReply();
        return Reply.OK;
    }
}
