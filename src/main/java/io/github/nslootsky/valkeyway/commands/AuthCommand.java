/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.InlineCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.server.Users;

/**
 * AUTH against the proxy's own users ({@code proxy.auth.*}), with Valkey's replies. Never forwarded:
 * that would authenticate a backend connection shared by every client, switching all of them to
 * that user.
 *
 * <p>Answered on the event loop, so commands pipelined behind it already see the new user.
 */
// [upstream:V7]
public class AuthCommand implements InlineCommandHandler {

    static final String WRONGPASS = "WRONGPASS invalid username-password pair or user is disabled.";

    private final Users users;

    public AuthCommand(Users users) {
        this.users = users;
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    @Override
    public boolean allowedBeforeAuth() {
        return true;
    }

    @Override
    public Reply execute(Request request) {
        return switch (request.argCount()) {
            case 1 -> users.authRequired()
                    ? authenticate(request.connection(), users, Users.DEFAULT, request.arg(0))
                    : Reply.error("ERR AUTH <password> called without any password configured for the default user. "
                            + "Are you sure your configuration is correct?");
            case 2 -> authenticate(request.connection(), users, request.argString(0), request.arg(1));
            case 0 -> Reply.error("ERR wrong number of arguments for 'auth' command");
            default -> Reply.error("ERR syntax error");
        };
    }

    /** Authenticates the connection as {@code user}; on a wrong password it keeps its current user. */
    static Reply authenticate(Connection connection, Users users, String user, byte[] password) {
        if (!users.check(user, password)) {
            return Reply.error(WRONGPASS);
        }
        connection.user(user);
        return Reply.OK;
    }
}
