/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.server.Users;
import io.github.nslootsky.valkeyway.transaction.Transaction;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;

/**
 * RESET, applied to the proxy's view of the connection: leaves MULTI, drops watches, selects
 * database 0, clears the name and the CLIENT REPLY and NO-EVICT/NO-TOUCH settings, and switches to
 * the default user (unauthenticated, if that user has a password), and back to RESP2.
 *
 * <p>Forwarding it would reset a backend connection shared by every client: on a database-1
 * connection that switched everyone's reads and writes to database 0.
 */
public class ResetCommand implements CommandHandler {

    private final WatchSessions watches;
    private final Users users;

    public ResetCommand(WatchSessions watches, Users users) {
        this.watches = watches;
        this.users = users;
    }

    @Override
    public boolean allowedBeforeAuth() {
        return true;
    }

    /** Like Valkey, RESET runs immediately even inside MULTI, and ends it. */
    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() != 0) {
            return Reply.error("ERR wrong number of arguments for 'reset' command");
        }
        Connection connection = request.connection();
        if (connection.inTransaction()) {
            Transaction.end(connection);
        }
        watches.end(connection);
        connection.db(0);
        connection.name(null);
        connection.noEvict(false);
        connection.noTouch(false);
        connection.replyMode(Connection.ReplyMode.ON);
        connection.protocol(2);
        connection.user(users.authRequired() ? null : Users.DEFAULT);
        return new Reply.Simple("RESET");
    }
}
