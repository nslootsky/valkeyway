/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterBatch;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import io.github.nslootsky.valkeyway.reply.Resp2Shapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * EXEC command handler. Executes queued transaction commands.
 * Uses ClusterBatch(true) for single-slot, ClusterBatch(false) for cross-slot.
 * Per-command failures are returned as error elements in the result array.
 */
@Command("exec")
public class ExecCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(ExecCommand.class);

    private final GlideClientCache glideClientCache;

    public ExecCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (!SessionState.isInTransaction(session)) {
            return RedisToken.error("ERR EXEC without MULTI");
        }

        String txError = SessionState.getTransactionError(session);
        List<byte[][]> buffered = new ArrayList<>(SessionState.getTransactionCommands(session));
        boolean crossSlot = SessionState.getTransactionSlots(session).size() > 1;
        SessionState.clearTransactionState(session);

        if (txError != null) {
            return RedisToken.error(txError);
        }

        if (buffered.isEmpty()) {
            return RedisToken.array();
        }

        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(session, glideClientCache);
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
                // Aborted by WATCH. Valkey replies with a null array (*-1); resp-server 1.0 cannot
                // encode one, so send the null bulk string, which clients also read as nil.
                log.debug("EXEC aborted (WATCH)");
                return RedisToken.nullString();
            }
            log.debug("EXEC OK results={}", results.length);
            for (int i = 0; i < results.length && i < buffered.size(); i++) {
                results[i] = Resp2Shapes.shape(buffered.get(i), results[i]);
            }
            return TokenUtils.toRedisArray(results);
        } catch (Exception e) {
            log.error("EXEC ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
