/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.FastThreadLocal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Collects single commands from different connections on one network thread and sends them to
 * Glide together at the end of the event-loop iteration: one command goes alone (with map-reply
 * routing), several go as one non-atomic batch, which costs Glide far less per command.
 *
 * <p>Ordering is unaffected: each connection has at most one unit in flight, and different
 * connections' commands have no order between them.
 *
 * <p>Relies on upstream behavior: [upstream:G3] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class Coalescer {

    private final BackendClients clients;
    private final Backend backend;
    private final FastThreadLocal<PerLoop> perLoop = new FastThreadLocal<>() {
        @Override
        protected PerLoop initialValue() {
            return new PerLoop();
        }
    };

    public Coalescer(BackendClients clients, Backend backend) {
        this.clients = clients;
        this.backend = backend;
    }

    /** Queues {@code args} for {@code db}; must be called on {@code loop}. The reply completes on {@code loop}. */
    public CompletionStage<Reply> submit(int db, byte[][] args, EventLoop loop) {
        return perLoop.get().add(db, args, loop);
    }

    private record Pending(byte[][] args, CompletableFuture<Reply> reply) {}

    private final class PerLoop {
        private final Map<Integer, List<Pending>> byDb = new HashMap<>();
        private boolean flushScheduled;

        CompletionStage<Reply> add(int db, byte[][] args, EventLoop loop) {
            CompletableFuture<Reply> reply = new CompletableFuture<>();
            byDb.computeIfAbsent(db, d -> new ArrayList<>()).add(new Pending(args, reply));
            if (!flushScheduled) {
                flushScheduled = true;
                loop.execute(() -> flush(loop));
            }
            return reply;
        }

        private void flush(EventLoop loop) {
            flushScheduled = false;
            for (var entry : byDb.entrySet()) {
                List<Pending> pending = entry.getValue();
                if (pending.isEmpty()) {
                    continue;
                }
                List<Pending> work = new ArrayList<>(pending);
                pending.clear();
                GlideClusterClient client = clients.getIfPresent(entry.getKey());
                if (client == null) {
                    for (Pending p : work) {
                        p.reply().complete(Reply.error("ERR backend client unavailable, retry"));
                    }
                } else if (work.size() == 1) {
                    sendOne(client, work.getFirst(), loop);
                } else {
                    sendBatch(client, work, loop);
                }
            }
        }

        private void sendOne(GlideClusterClient client, Pending p, EventLoop loop) {
            backend.call(client, p.args()).thenAcceptAsync(p.reply()::complete, loop);
        }

        private void sendBatch(GlideClusterClient client, List<Pending> work, EventLoop loop) {
            List<byte[][]> commands = new ArrayList<>(work.size());
            for (Pending p : work) {
                commands.add(p.args());
            }
            backend.pipeline(client, commands).thenAcceptAsync(replies -> {
                for (int i = 0; i < work.size(); i++) {
                    Pending p = work.get(i);
                    Reply reply = replies.get(i);
                    if (reply instanceof Reply.Error e && ServerErrors.rejectedWithoutRunning(e)) {
                        // [upstream:G3] Glide retries these for a single command but not inside a
                        // batch. Each command here is the only one its connection has in flight, so
                        // resending it alone can't reorder it against that connection's other commands.
                        sendOne(client, p, loop);
                    } else {
                        p.reply().complete(reply);
                    }
                }
            }, loop);
        }
    }
}
