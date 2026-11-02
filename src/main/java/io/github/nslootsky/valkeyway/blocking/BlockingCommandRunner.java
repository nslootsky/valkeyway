/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.backend.routing.ClusterSlots;
import io.github.nslootsky.valkeyway.backend.routing.SlotRoutes;
import glide.api.models.exceptions.RequestException;
import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.server.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runs a command that can block (BLPOP, XREAD BLOCK, WAIT, ...) on a leased, dedicated client, so it
 * never blocks the shared multiplexed connection every other proxy client uses. Time spent waiting
 * for a free client counts against the command's own timeout. [upstream:V5]
 */
public final class BlockingCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(BlockingCommandRunner.class);

    private final BlockingClientPool pool;
    private final Backend backend;
    private final BackendClients backendClients;

    public BlockingCommandRunner(BlockingClientPool pool, Backend backend, BackendClients backendClients) {
        this.pool = pool;
        this.backend = backend;
        this.backendClients = backendClients;
    }

    /** The client ID of {@code client}'s connection to the node of {@code slot}, or -1. */
    private long nodeClientId(GlideClusterClient client, int slot) {
        Map<Integer, Long> ids = nodeClientIds.computeIfAbsent(client, _ -> new ConcurrentHashMap<>());
        Long cached = ids.get(slot);
        if (cached != null) {
            return cached;
        }
        try {
            Object id = client.customCommand(new GlideString[]{GlideString.of("CLIENT"), GlideString.of("ID")},
                    SlotRoutes.primary(slot)).get(UNBLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS).getSingleValue();
            if (id instanceof Long l) {
                ids.put(slot, l);
                return l;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("CLIENT ID on a pooled client failed: {}", e.getMessage());
        }
        return -1;
    }

    static final String UNBLOCKED = "UNBLOCKED client unblocked via CLIENT UNBLOCK";
    private static final long UNBLOCK_TIMEOUT_SECONDS = 5;

    /** Per pooled client, its connection's client ID on the node of a slot (for CLIENT UNBLOCK). */
    private final Map<GlideClusterClient, Map<Integer, Long>> nodeClientIds =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Runs {@code args}, a blocking command on {@code keys} described by {@code spec}, for
     * {@code connection}. Blocks the calling (virtual) thread.
     *
     * <p>CLIENT UNBLOCK is passed on to the node as {@code CLIENT UNBLOCK <id>} for the pooled
     * connection the command waits on, so the node ends it and answers as it would (the timeout
     * reply, or an UNBLOCKED error). If that isn't possible (the ID isn't known yet, or the node no
     * longer has the command), the wait is ended here and the pooled client discarded.
     */
    public Reply run(Connection connection, byte[][] args, List<byte[]> keys, BlockingCommands.Spec spec)
            throws InterruptedException {
        Waiter waiter = new Waiter(connection.db(), keys.isEmpty() ? -1 : ClusterSlots.slot(keys.getFirst()));
        // XREADGROUP is also unblocked by its key being deleted, which INFO counts separately.
        boolean onNoKey = new String(args[0], StandardCharsets.US_ASCII).equalsIgnoreCase("xreadgroup");
        connection.block(keys, onNoKey, waiter::unblock);
        try {
            return run(args, spec, waiter);
        } finally {
            connection.unblocked();
        }
    }

    /** One blocking command's wait, which CLIENT UNBLOCK (from another client's thread) can end. */
    private final class Waiter {
        static final int NOT_SENT = 0;
        static final int SENT = 1;
        static final int ENDED_BEFORE_SENDING = 2;

        final int db;
        final int slot;
        /** Completed when the wait is ended here rather than by the node; true = with an error. */
        final CompletableFuture<Boolean> forced = new CompletableFuture<>();
        /** Whether the command was sent, or ended before it could be: decided once, by whoever is first. */
        final AtomicInteger state = new AtomicInteger(NOT_SENT);
        volatile long nodeClientId = -1;
        volatile CompletableFuture<?> reply;

        Waiter(int db, int slot) {
            this.db = db;
            this.slot = slot;
        }

        /**
         * Ends the wait. A command not sent yet is never sent. A sent one is ended on the node, so the
         * node answers it: CLIENT UNBLOCK is repeated until the command has arrived there, since a
         * command still on its way would otherwise block on the node later and take an element that
         * nobody receives. Only if the node can't be asked is the wait ended here, and the pooled
         * connection discarded.
         */
        void unblock(boolean withError) {
            if (state.compareAndSet(NOT_SENT, ENDED_BEFORE_SENDING)) {
                forced.complete(withError);
                return;
            }
            long id = nodeClientId;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UNBLOCK_TIMEOUT_SECONDS);
            while (id > 0 && System.nanoTime() < deadline) {
                CompletableFuture<?> pending = reply;
                if (pending != null && pending.isDone()) {
                    return; // the node has answered
                }
                try {
                    Object unblocked = backendClients.getClient(db).customCommand(new GlideString[]{
                                    GlideString.of("CLIENT"), GlideString.of("UNBLOCK"), GlideString.of(Long.toString(id)),
                                    GlideString.of(withError ? "ERROR" : "TIMEOUT")}, SlotRoutes.primary(slot))
                            .get(UNBLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS).getSingleValue();
                    if (Long.valueOf(1).equals(unblocked)) {
                        return; // the node ends the command and answers it
                    }
                    Thread.sleep(1); // not blocked on the node yet: the command is still on its way
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("CLIENT UNBLOCK on the node failed: {}", e.getMessage());
                    break;
                }
            }
            forced.complete(withError);
        }
    }

    private Reply run(byte[][] args, BlockingCommands.Spec spec, Waiter waiter) throws InterruptedException {
        long start = System.nanoTime();
        var lease = pool.acquire(waiter.db, spec.timeoutMillis());
        if (lease == null) {
            log.debug("BLOCKING {} timed out waiting for a pooled client", name(args));
            return timeoutReply(spec.timeoutReply());
        }
        try {
            if (waiter.slot >= 0) {
                waiter.nodeClientId = nodeClientId(lease.resource(), waiter.slot);
            }
            if (!waiter.state.compareAndSet(Waiter.NOT_SENT, Waiter.SENT)) {
                return unblockedReply(spec, waiter.forced.join()); // unblocked before the command was sent
            }
            long waitedMillis = (System.nanoTime() - start) / 1_000_000;
            byte[][] effective = spec.withRemainingTimeout(args, waitedMillis);
            CompletableFuture<ClusterValue<Object>> reply = backend.send(lease.resource(), effective);
            waiter.reply = reply;
            CompletableFuture.anyOf(reply, waiter.forced).get();
            if (!reply.isDone()) {
                lease.discard(); // the command may still be pending on its connection
                return unblockedReply(spec, waiter.forced.join());
            }
            return Backend.toReply(args, reply.get());
        } catch (ExecutionException e) {
            // A server error leaves the connection usable. Anything else (timeout, connection loss)
            // may leave the blocking command pending on it, so don't hand it to the next caller.
            if (!(e.getCause() instanceof RequestException)) {
                lease.discard();
            }
            log.debug("ERR {} error={}", name(args), e.getMessage());
            return ServerErrors.error(e);
        } catch (InterruptedException e) {
            lease.discard();
            throw e;
        } finally {
            lease.close();
        }
    }

    private static Reply unblockedReply(BlockingCommands.Spec spec, boolean withError) {
        return withError ? Reply.error(UNBLOCKED) : timeoutReply(spec.timeoutReply());
    }

    private static Reply timeoutReply(BlockingCommands.TimeoutReply reply) {
        return switch (reply) {
            case NULL -> Reply.Null.ARRAY;
            case ZERO -> Reply.ZERO;
            case ZERO_PAIR -> Reply.array(Reply.ZERO, Reply.ZERO);
        };
    }

    private static String name(byte[][] args) {
        return new String(args[0], StandardCharsets.US_ASCII);
    }
}
