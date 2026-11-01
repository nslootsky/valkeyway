/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterBatch;
import glide.api.models.ClusterValue;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.backend.routing.MapReplyCommands;
import io.github.nslootsky.valkeyway.backend.routing.SlotRoutes;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.backend.replies.GlideReplies;
import io.github.nslootsky.valkeyway.backend.replies.Resp2Shapes;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sends commands to the cluster through Glide and turns the results into the replies a standalone
 * server would send.
 *
 * <p>Relies on upstream behavior: [upstream:G4] [upstream:G5] [upstream:G10] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class Backend {

    private final CommandKeyExtractor keys;

    public Backend(CommandKeyExtractor keys) {
        this.keys = keys;
    }

    /**
     * Sends one command; completes exceptionally on an error. Commands that can return a top-level map
     * are routed explicitly to their slot's primary when all their keys share a slot, which keeps Glide
     * from misreading the reply (see {@link SlotRoutes}). Everything else keeps Glide's own routing
     * (fan-out, splitting, aggregation), which is also its faster path.
     *
     * <p>If Glide returns a redirect instead of following it, the command is resent as a one-command
     * batch: for single commands to a migrating slot under concurrent traffic, Glide often fails to
     * follow ASK, while inside a batch it follows it reliably. The node rejected the command without
     * running it, so resending is safe.
     */
    public CompletableFuture<ClusterValue<Object>> send(GlideClusterClient client, byte[][] args) {
        return sendOnce(client, args).exceptionallyCompose(e -> ServerErrors.isUnfollowedRedirect(e)
                ? sendAsBatch(client, args)
                : CompletableFuture.failedFuture(e));
    }

    /** {@link #send}, with the result converted; errors become error replies, so this never fails. */
    public CompletableFuture<Reply> call(GlideClusterClient client, byte[][] args) {
        return send(client, args).handle((result, error) -> error != null ? ServerErrors.error(error) : toReply(args, result));
    }

    /** {@link #call}, waiting for the reply. For handlers running on a virtual thread. */
    public Reply callAndWait(GlideClusterClient client, byte[][] args) {
        try {
            return call(client, args).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            return ServerErrors.error(e);
        }
    }

    /**
     * Sends {@code commands} as one non-atomic batch, which Glide runs in order on each node (separately
     * submitted commands can be reordered). One reply per command, in order; errors become error
     * replies, so this never fails. Batch results don't go through {@code ClusterValue}, so map replies
     * keep their order and bytes without explicit routing.
     */
    public CompletableFuture<List<Reply>> pipeline(GlideClusterClient client, List<byte[][]> commands) {
        return client.exec(nonAtomic(commands), false).handle((results, error) -> {
            List<Reply> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) {
                replies.add(error != null ? ServerErrors.error(error)
                        : results == null || i >= results.length ? Reply.error("ERR missing reply in batch")
                        : toReply(commands.get(i), results[i]));
            }
            return replies;
        });
    }

    /** A non-atomic batch of {@code commands}, with binary output. */
    public static ClusterBatch nonAtomic(List<byte[][]> commands) {
        ClusterBatch batch = new ClusterBatch(false).withBinaryOutput();
        for (byte[][] command : commands) {
            batch.customCommand(GlideArgs.of(command));
        }
        return batch;
    }

    /** The reply to {@code args} from a single command's result. */
    public static Reply toReply(byte[][] args, ClusterValue<Object> result) {
        return result.hasSingleData() ? toReply(args, result.getSingleValue()) : GlideReplies.toReply(result);
    }

    /** The reply to {@code args} from one value: a single-node result, or one element of a batch result. */
    public static Reply toReply(byte[][] args, Object value) {
        return GlideReplies.toReply(Resp2Shapes.shape(args, value));
    }

    private CompletableFuture<ClusterValue<Object>> sendOnce(GlideClusterClient client, byte[][] args) {
        int slot = MapReplyCommands.mayReturnMap(args) ? keys.singleSlot(client, args) : CommandKeyExtractor.NO_SLOT;
        return slot == CommandKeyExtractor.NO_SLOT
                ? client.customCommand(GlideArgs.of(args))
                : client.customCommand(GlideArgs.of(args), SlotRoutes.primary(slot));
    }

    private static CompletableFuture<ClusterValue<Object>> sendAsBatch(GlideClusterClient client, byte[][] args) {
        return client.exec(nonAtomic(List.<byte[][]>of(args)), false).thenCompose(results -> {
            Object result = results == null || results.length == 0 ? null : results[0];
            // ofSingleValue, not of(): of() misreads map replies as multi-node results [upstream:G5].
            return result instanceof Throwable failure
                    ? CompletableFuture.<ClusterValue<Object>>failedFuture(failure)
                    : CompletableFuture.completedFuture(ClusterValue.ofSingleValue(result));
        });
    }
}
