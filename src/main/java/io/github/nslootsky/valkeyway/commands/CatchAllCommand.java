/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.backend.Coalescer;
import io.github.nslootsky.valkeyway.blocking.BlockingCommandRunner;
import io.github.nslootsky.valkeyway.blocking.BlockingCommands;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.backend.routing.MapReplyCommands;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.AsyncCommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;
import io.netty.channel.EventLoop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Every command without a dedicated handler: sent to the cluster, or queued while the connection is
 * in MULTI.
 *
 * <p>Most commands start asynchronously on the network thread (see {@link #canRunAsync}), so a
 * connection can pipeline them, and single commands from different connections are coalesced into
 * shared batches. The rest run through {@link #execute} on a virtual thread: queueing in a
 * transaction, blocking commands, and anything that would need a round trip to set up.
 */
public class CatchAllCommand implements AsyncCommandHandler {

    private static final Executor FALLBACK = Executors.newVirtualThreadPerTaskExecutor();

    private final BackendClients clients;
    private final Backend backend;
    private final CommandKeyExtractor keys;
    private final Coalescer coalescer;
    private final BlockingCommandRunner blocking;
    private final TransactionQueue transactions;

    /**
     * @param coalescer {@code null} to send every single command on its own
     */
    public CatchAllCommand(BackendClients clients, Backend backend, CommandKeyExtractor keys, Coalescer coalescer,
                           BlockingCommandRunner blocking, TransactionQueue transactions) {
        this.clients = clients;
        this.backend = backend;
        this.keys = keys;
        this.coalescer = coalescer;
        this.blocking = blocking;
        this.transactions = transactions;
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    /**
     * Async unless the command must be queued (MULTI), may block (BLPOP & co. use a leased client), or
     * would block the network thread to set something up (the database's first Glide client, the
     * COMMAND table needed to route a map reply).
     */
    @Override
    public boolean canRunAsync(Request request) {
        Connection connection = request.connection();
        byte[][] args = request.args();
        if (connection.inTransaction() || BlockingCommands.detect(args).isPresent()) {
            return false;
        }
        if (clients.getIfPresent(connection.db()) == null) {
            return false;
        }
        return !MapReplyCommands.mayReturnMap(args) || keys.isLoaded();
    }

    @Override
    public CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor) {
        int db = request.connection().db();
        GlideClusterClient client = clients.getIfPresent(db);
        if (client == null) {
            // Gone since canRunAsync: create it off the network thread.
            return CompletableFuture.supplyAsync(() -> executeUnchecked(request), FALLBACK);
        }
        if (coalescer != null && replyExecutor instanceof EventLoop loop && loop.inEventLoop()) {
            return coalescer.submit(db, request.args(), loop);
        }
        return backend.call(client, request.args()).thenApplyAsync(Function.identity(), replyExecutor);
    }

    @Override
    public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
        GlideClusterClient client = clients.getIfPresent(requests.getFirst().connection().db());
        if (client == null) {
            return CompletableFuture.supplyAsync(() -> requests.stream().map(this::executeUnchecked).toList(), FALLBACK);
        }
        List<byte[][]> commands = new ArrayList<>(requests.size());
        for (Request request : requests) {
            commands.add(request.args());
        }
        return backend.pipeline(client, commands).thenApplyAsync(Function.identity(), replyExecutor);
    }

    @Override
    public Reply execute(Request request) throws InterruptedException {
        Connection connection = request.connection();
        byte[][] args = request.args();
        if (connection.inTransaction()) {
            return transactions.queue(connection, args);
        }
        Optional<BlockingCommands.Spec> spec = BlockingCommands.detect(args);
        if (spec.isPresent()) {
            return blocking.run(connection.db(), args, spec.get());
        }
        return backend.callAndWait(clients.getClient(connection.db()), args);
    }

    private Reply executeUnchecked(Request request) {
        try {
            return execute(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        }
    }
}
