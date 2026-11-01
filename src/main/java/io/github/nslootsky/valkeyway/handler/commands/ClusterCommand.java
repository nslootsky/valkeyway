/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.GlideReplies;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
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

    private final GlideClientCache glideClientCache;

    public ClusterCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'cluster' command");
        }
        if (STANDALONE_ERROR_SUBCOMMANDS.contains(request.argString(0).toUpperCase())) {
            return Reply.error("ERR This instance has cluster support disabled");
        }
        try {
            GlideClusterClient client = glideClientCache.getClient(request.connection().db());
            byte[][] args = request.args();
            GlideString[] cmdArgs = new GlideString[args.length];
            for (int i = 0; i < args.length; i++) {
                cmdArgs[i] = GlideString.of(args[i]);
            }
            return GlideReplies.toReply(client.customCommand(cmdArgs).get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            return ServerErrors.error(e);
        }
    }
}
