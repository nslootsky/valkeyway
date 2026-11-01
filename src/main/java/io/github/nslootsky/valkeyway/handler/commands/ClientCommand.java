/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * CLIENT. The connection-scoped subcommands (ID, GETNAME, SETNAME, SETINFO) are answered from the
 * proxy's own connection state; the rest are sent to the cluster.
 */
public class ClientCommand implements CommandHandler {

    private final GlideClientCache clients;
    private final Backend backend;

    public ClientCommand(GlideClientCache clients, Backend backend) {
        this.clients = clients;
        this.backend = backend;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() >= 1) {
            String sub = request.argString(0);
            if (sub.equalsIgnoreCase("SETINFO")) {
                return Reply.OK;
            }
            if (sub.equalsIgnoreCase("ID") && request.argCount() == 1) {
                return Reply.integer(request.connection().id());
            }
            if (sub.equalsIgnoreCase("GETNAME") && request.argCount() == 1) {
                String name = request.connection().name();
                return name == null ? Reply.Null.BULK : Reply.bulk(name);
            }
            if (sub.equalsIgnoreCase("SETNAME") && request.argCount() == 2) {
                String name = request.argString(1);
                for (int i = 0; i < name.length(); i++) {
                    if (name.charAt(i) <= ' ' || name.charAt(i) > '~') {
                        return Reply.error("ERR Client names cannot contain spaces, newlines or special characters.");
                    }
                }
                request.connection().name(name.isEmpty() ? null : name);
                return Reply.OK;
            }
        }
        return backend.callAndWait(clients.getClient(request.connection().db()), request.args());
    }
}
