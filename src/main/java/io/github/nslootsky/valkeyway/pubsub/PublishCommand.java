/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.AsyncCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * PUBLISH and SPUBLISH: sent to the cluster as usual, answered with the number of proxy clients that
 * receive the message, as a standalone server counts its subscribers. The node's own count would
 * count the proxy's shared subscription once, or not at all if it's on another node.
 */
public class PublishCommand implements AsyncCommandHandler {

    private final AsyncCommandHandler forward;
    private final PubSubHub hub;
    private final boolean shard;

    public PublishCommand(AsyncCommandHandler forward, PubSubHub hub, boolean shard) {
        this.forward = forward;
        this.hub = hub;
        this.shard = shard;
    }

    /** Queued in MULTI like any command; the count in EXEC's reply is then the node's. */
    @Override
    public InTransaction inTransaction() {
        return InTransaction.QUEUE;
    }

    @Override
    public boolean canRunAsync(Request request) {
        return forward.canRunAsync(request);
    }

    @Override
    public CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor) {
        return forward.executeAsync(request, replyExecutor).thenApply(reply -> count(request, reply));
    }

    @Override
    public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
        return forward.executeBatchAsync(requests, replyExecutor).thenApply(replies -> {
            List<Reply> counted = new ArrayList<>(replies.size());
            for (int i = 0; i < replies.size(); i++) {
                counted.add(count(requests.get(i), replies.get(i)));
            }
            return counted;
        });
    }

    @Override
    public Reply execute(Request request) throws Exception {
        return count(request, forward.execute(request));
    }

    /** Replaces a successful PUBLISH's count; errors (wrong arity, ...) pass through. */
    private Reply count(Request request, Reply reply) {
        return reply instanceof Reply.Int && request.argCount() == 2
                ? Reply.integer(hub.receivers(shard, request.arg(0)))
                : reply;
    }
}
