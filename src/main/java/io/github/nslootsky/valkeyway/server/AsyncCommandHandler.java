/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * A handler that can start commands without blocking the network thread.
 *
 * <p>A connection has at most one backend unit in flight: a single command, or a batch of every
 * consecutive async command that queued up meanwhile (a pipeline). A batch must be executed so that
 * its commands take effect in order, as on a standalone server.
 *
 * <p>All methods are called on the connection's network thread, in request order, and must not
 * block. A request the handler can't start that way (it needs state that isn't ready, or it may
 * block) is run through {@link #execute} on a virtual thread instead, as a barrier.
 */
public interface AsyncCommandHandler extends CommandHandler {

    /** Whether {@code request} can be started asynchronously right now. */
    boolean canRunAsync(Request request);

    /**
     * Starts {@code request}. The returned stage should complete on {@code replyExecutor} (the
     * connection's network thread), where the reply is then written in order.
     */
    CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor);

    /**
     * Starts several requests (two or more) as one ordered batch. The returned stage must complete
     * on {@code replyExecutor} with one reply per request, in order.
     */
    CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor);
}
