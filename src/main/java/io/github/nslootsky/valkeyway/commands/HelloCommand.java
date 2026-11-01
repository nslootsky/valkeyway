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

import java.util.List;

/**
 * {@code HELLO [protover [AUTH username password] [SETNAME clientname]]}: switches the connection to
 * RESP2 or RESP3 and answers like a standalone server, without contacting the cluster. AUTH
 * authenticates against the proxy's users, as the AUTH command does.
 */
// [upstream:V7]
public class HelloCommand implements InlineCommandHandler {

    private final Users users;

    public HelloCommand(Users users) {
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
        Connection connection = request.connection();
        int argc = request.argCount();
        long version = connection.protocol();
        if (argc >= 1) {
            try {
                version = Long.parseLong(request.argString(0));
            } catch (NumberFormatException e) {
                return Reply.error("ERR Protocol version is not an integer or out of range");
            }
            if (version < 2 || version > 3) {
                return Reply.error("NOPROTO unsupported protocol version");
            }
        }
        String user = null;
        byte[] password = null;
        String name = null;
        for (int i = 1; i < argc; i++) {
            String option = request.argString(i);
            int more = argc - 1 - i;
            if (option.equalsIgnoreCase("AUTH") && more >= 2) {
                user = request.argString(i + 1);
                password = request.arg(i + 2);
                i += 2;
            } else if (option.equalsIgnoreCase("SETNAME") && more >= 1) {
                name = request.argString(i + 1);
                i += 1;
            } else {
                return Reply.error("ERR Syntax error in HELLO option '" + option + "'");
            }
        }
        if (user != null) {
            Reply auth = AuthCommand.authenticate(connection, users, user, password);
            if (auth instanceof Reply.Error) {
                return auth;
            }
        }
        if (connection.user() == null) {
            return Reply.error("NOAUTH HELLO must be called with the client already authenticated, otherwise the "
                    + "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and select "
                    + "the RESP protocol version at the same time");
        }
        if (name != null) {
            Reply setName = ClientCommand.setName(connection, name);
            if (setName instanceof Reply.Error) {
                return setName;
            }
        }
        connection.protocol((int) version);
        return new Reply.MapReply(List.of(
                Reply.bulk("server"), Reply.bulk("valkeyway"),
                Reply.bulk("version"), Reply.bulk("0.1.0"),
                Reply.bulk("proto"), Reply.integer(version),
                Reply.bulk("id"), Reply.integer(connection.id()),
                Reply.bulk("mode"), Reply.bulk("standalone"),
                Reply.bulk("role"), Reply.bulk("master"),
                Reply.bulk("modules"), Reply.EMPTY_ARRAY));
    }
}
