package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterBatch;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * EXEC command handler. Executes queued transaction commands.
 * Uses ClusterBatch(true) for single-slot, ClusterBatch(false) for cross-slot.
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
        List<String[]> buffered = new ArrayList<>(SessionState.getTransactionCommands(session));
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
            ClusterBatch batch = new ClusterBatch(!crossSlot);
            for (String[] cmdArgs : buffered) {
                batch.customCommand(cmdArgs);
            }

            log.debug("EXEC {} commands crossSlot={}", buffered.size(), crossSlot);
            Object[] results = client.exec(batch, true).get();
            log.debug("EXEC OK results={}", results.length);
            return TokenUtils.toRedisArray(results);
        } catch (Exception e) {
            log.error("EXEC ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
