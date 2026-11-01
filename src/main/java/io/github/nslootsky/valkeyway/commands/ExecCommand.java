/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import java.nio.charset.StandardCharsets;
import io.github.nslootsky.valkeyway.transaction.Transaction;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterBatch;
import glide.api.models.commands.batch.ClusterBatchOptions;
import io.github.nslootsky.valkeyway.backend.routing.SlotRoutes;
import io.github.nslootsky.valkeyway.ValkeywayProperties.CrossSlot;
import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.backend.GlideArgs;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.backend.replies.GlideReplies;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.transaction.WatchSession;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * EXEC: runs the queued transaction.
 *
 * <ul>
 *   <li>All keys (queued and watched) in one slot: an atomic ClusterBatch, i.e. MULTI/EXEC on that
 *       slot's node. With WATCH, it runs on the connection's own backend client, where the watches are.</li>
 *   <li>Keys in several slots, which a cluster can't run atomically: rejected with CROSSSLOT, like a
 *       cluster node does, unless {@code proxy.transactions.cross-slot=pipeline}, which runs the
 *       commands in order without atomicity. With WATCH it is always rejected.</li>
 *   <li>A command rejected while queueing (unknown, wrong arity) makes EXEC fail with EXECABORT.</li>
 * </ul>
 *
 * <p>Per-command failures are returned as error elements in the result array, as Valkey does.
 */
public class ExecCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    private static final Logger log = LoggerFactory.getLogger(ExecCommand.class);

    static final String CROSSSLOT = "CROSSSLOT Keys in request don't hash to the same slot";

    private final BackendClients backendClients;
    private final WatchSessions watches;
    private final CrossSlot crossSlotMode;

    public ExecCommand(BackendClients backendClients, WatchSessions watches, CrossSlot crossSlotMode) {
        this.backendClients = backendClients;
        this.watches = watches;
        this.crossSlotMode = crossSlotMode;
    }

    @Override
    public Reply execute(Request request) {
        Connection connection = request.connection();
        if (!connection.inTransaction()) {
            return Reply.error("ERR EXEC without MULTI");
        }

        Transaction transaction = Transaction.end(connection);
        String txError = transaction.error();
        List<byte[][]> buffered = transaction.commands();
        Set<Integer> slots = new HashSet<>(transaction.slots());
        // EXEC ends the watch whatever happens next.
        WatchSession watch = watches.take(connection);
        if (watch != null) {
            slots.addAll(watch.slots());
        }
        boolean crossSlot = slots.size() > 1;

        if (txError != null) {
            release(watch);
            return Reply.error(txError);
        }
        if (watch != null && watch.broken()) {
            log.debug("EXEC aborted: the watch couldn't be kept");
            release(watch);
            return Reply.Null.ARRAY; // [upstream:V2] aborted EXEC
        }
        if (crossSlot && (watch != null || crossSlotMode == CrossSlot.REJECT)) {
            release(watch);
            return Reply.error(CROSSSLOT);
        }
        if (buffered.isEmpty()) {
            if (watch == null) {
                return Reply.EMPTY_ARRAY;
            }
            // An empty EXEC still reports whether a watched key changed; run a harmless command on
            // the watched slot so the batch goes to the node holding the watches.
            buffered = List.<byte[][]>of(new byte[][]{"EXISTS".getBytes(StandardCharsets.US_ASCII), watch.firstKey()});
            Reply reply = run(connection, buffered, false, watch, slots);
            return reply instanceof Reply.Array ? Reply.EMPTY_ARRAY : reply;
        }
        return run(connection, buffered, crossSlot, watch, slots);
    }

    /**
     * @param slots the slots of the queued and watched keys; with exactly one, the batch is sent to
     *        that slot's node. That's what makes a WATCH count for a transaction of keyless commands
     *        (MULTI, PING, EXEC): without a route, Glide sends it to any node, which knows nothing of
     *        the watch.
     */
    private Reply run(Connection connection, List<byte[][]> buffered, boolean crossSlot, WatchSession watch,
                      Set<Integer> slots) {
        boolean completed = false;
        try {
            GlideClusterClient client = watch != null ? watch.client() : backendClients.getClient(connection.db());
            ClusterBatch batch = new ClusterBatch(!crossSlot).withBinaryOutput();
            for (byte[][] command : buffered) {
                batch.customCommand(GlideArgs.of(command));
            }

            log.debug("EXEC {} commands crossSlot={} watched={}", buffered.size(), crossSlot, watch != null);
            // raiseOnError=false: a failing command yields an error element in the reply array,
            // matching Valkey's EXEC semantics, instead of failing the whole reply.
            Object[] results = slots.size() == 1
                    ? client.exec(batch, false, ClusterBatchOptions.builder()
                            .route(SlotRoutes.primary(slots.iterator().next())).build()).get()
                    : client.exec(batch, false).get();
            completed = true;
            if (results == null) {
                log.debug("EXEC aborted (WATCH)");
                return Reply.Null.ARRAY; // [upstream:V2] aborted EXEC
            }
            List<Reply> replies = new ArrayList<>(results.length);
            for (int i = 0; i < results.length; i++) {
                replies.add(i < buffered.size() ? Backend.toReply(buffered.get(i), results[i]) : GlideReplies.toReply(results[i]));
            }
            return new Reply.Array(replies);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            log.debug("EXEC ERR {}", e.getMessage());
            return ServerErrors.error(e);
        } finally {
            if (watch != null) {
                watch.releaseAfterExec(completed);
            }
        }
    }

    /** Ends a watch whose EXEC didn't reach the server. */
    private static void release(WatchSession watch) {
        if (watch != null) {
            watch.close();
        }
    }
}
