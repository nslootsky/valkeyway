/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * MULTI: starts a transaction; subsequent commands are queued until EXEC or DISCARD.
 */
public class MultiCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    @Override
    public Reply execute(Request request) {
        Connection connection = request.connection();
        if (connection.inTransaction()) {
            // Like Valkey 9, this also discards the transaction (EXEC answers EXECABORT).
            connection.transactionError(CatchAllCommand.EXECABORT);
            return Reply.error(io.github.nslootsky.valkeyway.cluster.CommandKeyExtractor.notAllowedInTransaction("multi"));
        }
        connection.clearTransaction();
        connection.inTransaction(true);
        return Reply.OK;
    }
}
