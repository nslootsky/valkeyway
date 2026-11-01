/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.transaction.WatchClientPool;
import io.github.nslootsky.valkeyway.transaction.Transaction;
import io.github.nslootsky.valkeyway.backend.GlideArgs;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;
import io.github.nslootsky.valkeyway.transaction.WatchSession;

import java.util.Arrays;

/**
 * WATCH and UNWATCH, on the connection's own backend client (see
 * {@link WatchClientPool}).
 */
public class WatchCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    private final WatchSessions sessions;

    public WatchCommand(WatchSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public Reply execute(Request request) {
        Connection connection = request.connection();
        byte[][] args = request.args();
        boolean watch = args[0].length == 5;
        if (watch) {
            if (args.length < 2) {
                return Reply.error("ERR wrong number of arguments for 'watch' command");
            }
            if (connection.inTransaction()) {
                // Like Valkey 9, this also discards the transaction (EXEC answers EXECABORT).
                return TransactionQueue.reject(connection, CommandKeyExtractor.notAllowedInTransaction("watch"));
            }
            return watch(connection, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length != 1) {
            return Reply.error("ERR wrong number of arguments for 'unwatch' command");
        }
        if (connection.inTransaction()) {
            // Queued like any other command; it runs inside EXEC on the same backend client.
            Transaction.current(connection).commands().add(args);
            return Reply.QUEUED;
        }
        sessions.end(connection);
        return Reply.OK;
    }

    private Reply watch(Connection connection, byte[][] keys) {
        WatchSession session;
        try {
            session = sessions.forWatch(connection);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        }
        if (session == null) {
            return Reply.error("ERR backend unavailable: no dedicated connection free for WATCH "
                    + "(proxy.transactions.watch-pool.max-size); the command did not run");
        }
        if (session.broken()) {
            // Keys in another database than the session's; EXEC will abort.
            return Reply.OK;
        }
        try {
            session.client().customCommand(GlideArgs.of(new String[]{"WATCH"}, keys)).get();
            sessions.watched(session, keys);
            return Reply.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.markBroken();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            // Unknown whether the server registered the watch; make EXEC abort rather than run unprotected.
            session.markBroken();
            return ServerErrors.error(e);
        }
    }
}
