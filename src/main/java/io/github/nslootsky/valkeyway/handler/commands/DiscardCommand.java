/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * DISCARD: aborts the current transaction.
 */
public class DiscardCommand implements CommandHandler {

    @Override
    public Reply execute(Request request) {
        if (!request.connection().inTransaction()) {
            return Reply.error("ERR DISCARD without MULTI");
        }
        request.connection().clearTransaction();
        return Reply.OK;
    }
}
