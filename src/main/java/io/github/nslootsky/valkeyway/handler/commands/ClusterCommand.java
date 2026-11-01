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

import java.util.Set;

/**
 * CLUSTER: INFO and NODES answer like a standalone server (cluster support disabled); other
 * subcommands are proxied to the cluster.
 */
public class ClusterCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    /** Subcommands that must return standalone-mode errors (real standalone servers don't support cluster mode). */
    private static final Set<String> STANDALONE_ERROR_SUBCOMMANDS = Set.of("INFO", "NODES");

    private final GlideClientCache clients;
    private final Backend backend;

    public ClusterCommand(GlideClientCache clients, Backend backend) {
        this.clients = clients;
        this.backend = backend;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'cluster' command");
        }
        if (STANDALONE_ERROR_SUBCOMMANDS.contains(request.argString(0).toUpperCase(java.util.Locale.ROOT))) {
            return Reply.error("ERR This instance has cluster support disabled");
        }
        return backend.callAndWait(clients.getClient(request.connection().db()), request.args());
    }
}
