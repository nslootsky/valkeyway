/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.transaction.Transaction;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;
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
            return TransactionQueue.reject(connection, CommandKeyExtractor.notAllowedInTransaction("multi"));
        }
        Transaction.begin(connection);
        return Reply.OK;
    }
}
