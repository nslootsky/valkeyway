/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * SELECT: switches the connection's database. The database's Glide client is created lazily on
 * first use. Not allowed inside MULTI.
 */
public class SelectCommand implements CommandHandler {

    @Override
    public Reply execute(Request request) {
        if (request.connection().inTransaction()) {
            return Reply.error("ERR SELECT not allowed in MULTI/EXEC");
        }
        if (request.argCount() != 1) {
            return Reply.error("ERR wrong number of arguments for 'select' command");
        }
        try {
            int db = Integer.parseInt(request.argString(0));
            if (db < 0) {
                return Reply.error("ERR DB index is out of range");
            }
            request.connection().db(db);
            return Reply.OK;
        } catch (NumberFormatException e) {
            return Reply.error("ERR value is not an integer or out of range");
        }
    }
}
