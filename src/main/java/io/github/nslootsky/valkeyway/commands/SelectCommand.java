/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.transaction.WatchSessions;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * SELECT: switches the connection's database. The database's Glide client is created lazily on
 * first use. Not allowed inside MULTI.
 */
public class SelectCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    private static final String OUT_OF_RANGE = "ERR DB index is out of range";
    /** How long a database the cluster refused is answered from memory, without trying again. */
    private static final long INVALID_FOR_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);

    private final WatchSessions watches;
    private final BackendClients backendClients;
    private final java.util.Map<Integer, Long> invalid = new java.util.concurrent.ConcurrentHashMap<>();

    public SelectCommand(WatchSessions watches, BackendClients backendClients) {
        this.watches = watches;
        this.backendClients = backendClients;
    }

    @Override
    public Reply execute(Request request) {
        if (request.connection().inTransaction()) {
            // Running SELECT inside EXEC would switch the database of the backend connection that every
            // client shares. Discard the transaction rather than run its commands in the wrong database.
            return TransactionQueue.reject(request.connection(),
                    "ERR Command 'select' is not supported inside a transaction by this proxy");
        }
        if (request.argCount() != 1) {
            return Reply.error("ERR wrong number of arguments for 'select' command");
        }
        try {
            int db = Integer.parseInt(request.argString(0));
            if (db < 0) {
                return Reply.error("ERR DB index is out of range");
            }
            // The database's backend client, created now if it's the first use: it connects only if
            // the cluster has that database. Otherwise every later command would try again.
            Long invalidUntil = invalid.get(db);
            if (invalidUntil != null && System.nanoTime() < invalidUntil) {
                return Reply.error(OUT_OF_RANGE);
            }
            try {
                backendClients.getClient(db);
                invalid.remove(db);
            } catch (RuntimeException e) {
                // Glide only says the connection failed. If database 0 works, the cluster is
                // reachable and refused this database.
                if (db != 0 && reachable()) {
                    if (invalid.size() < 1000) {
                        invalid.put(db, System.nanoTime() + INVALID_FOR_NANOS);
                    }
                    return Reply.error(OUT_OF_RANGE);
                }
                return ServerErrors.error(e);
            }
            request.connection().db(db);
            watches.selected(request.connection());
            return Reply.OK;
        } catch (NumberFormatException e) {
            return Reply.error("ERR value is not an integer or out of range");
        }
    }

    private boolean reachable() {
        try {
            backendClients.getClient(0);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
