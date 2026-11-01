/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterBatch;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.GlideReplies;
import io.github.nslootsky.valkeyway.reply.Resp2Shapes;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * EXEC: runs the queued transaction. Uses an atomic ClusterBatch when all keys share a slot and a
 * non-atomic one otherwise. Per-command failures are returned as error elements in the result array.
 */
public class ExecCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecCommand.class);

    private final GlideClientCache glideClientCache;

    public ExecCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public Reply execute(Request request) {
        Connection connection = request.connection();
        if (!connection.inTransaction()) {
            return Reply.error("ERR EXEC without MULTI");
        }

        String txError = connection.transactionError();
        List<byte[][]> buffered = new ArrayList<>(connection.transactionCommands());
        boolean crossSlot = connection.transactionSlots().size() > 1;
        connection.clearTransaction();

        if (txError != null) {
            return Reply.error(txError);
        }
        if (buffered.isEmpty()) {
            return Reply.EMPTY_ARRAY;
        }

        try {
            GlideClusterClient client = glideClientCache.getClient(connection.db());
            ClusterBatch batch = new ClusterBatch(!crossSlot).withBinaryOutput();
            for (byte[][] cmdBytes : buffered) {
                GlideString[] cmdArgs = new GlideString[cmdBytes.length];
                for (int i = 0; i < cmdBytes.length; i++) {
                    cmdArgs[i] = GlideString.of(cmdBytes[i]);
                }
                batch.customCommand(cmdArgs);
            }

            log.debug("EXEC {} commands crossSlot={}", buffered.size(), crossSlot);
            // raiseOnError=false: a failing command yields an error element in the reply array,
            // matching Valkey's EXEC semantics, instead of failing the whole reply.
            Object[] results = client.exec(batch, false).get();
            if (results == null) {
                log.debug("EXEC aborted (WATCH)");
                return Reply.Null.ARRAY; // [upstream:V2] aborted EXEC
            }
            List<Reply> replies = new ArrayList<>(results.length);
            for (int i = 0; i < results.length; i++) {
                Object value = i < buffered.size() ? Resp2Shapes.shape(buffered.get(i), results[i]) : results[i];
                replies.add(GlideReplies.toReply(value));
            }
            return new Reply.Array(replies);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            log.debug("EXEC ERR {}", e.getMessage());
            return ServerErrors.error(e);
        }
    }
}
