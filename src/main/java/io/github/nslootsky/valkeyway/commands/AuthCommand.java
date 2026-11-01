/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * AUTH, answered as a standalone server with no password configured: the proxy has no users of its
 * own yet (client authentication is planned). Forwarding it would authenticate a backend connection
 * shared by every client, switching all of them to that user.
 */
public class AuthCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    @Override
    public Reply execute(Request request) {
        return switch (request.argCount()) {
            case 1 -> Reply.error("ERR AUTH <password> called without any password configured for the default user. "
                    + "Are you sure your configuration is correct?");
            // The default user has no password, so any password works for it.
            case 2 -> request.argString(0).equals("default")
                    ? Reply.OK
                    : Reply.error("WRONGPASS invalid username-password pair or user is disabled.");
            case 0 -> Reply.error("ERR wrong number of arguments for 'auth' command");
            default -> Reply.error("ERR syntax error");
        };
    }
}
