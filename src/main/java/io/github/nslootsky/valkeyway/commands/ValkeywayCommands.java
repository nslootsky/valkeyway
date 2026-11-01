/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import java.util.Locale;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.backend.Coalescer;
import io.github.nslootsky.valkeyway.blocking.BlockingCommandRunner;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;
import io.github.nslootsky.valkeyway.transaction.WatchClientPool;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;

import io.github.nslootsky.valkeyway.blocking.BlockingClientPool;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.ops.MetricsCollector;
import io.github.nslootsky.valkeyway.commands.ScanCursorStore;
import io.github.nslootsky.valkeyway.server.CommandTable;

/**
 * The proxy's command table: dedicated handlers for commands answered or adapted by the proxy,
 * and {@link CatchAllCommand} for everything forwarded to the cluster.
 */
public final class ValkeywayCommands {

    private ValkeywayCommands() {}

    public static CommandTable create(BackendClients backendClients, ScanCursorStore scanCursorStore,
                                      MetricsCollector metrics, BlockingClientPool blockingPool,
                                      WatchClientPool watchPool, ValkeywayProperties props) {
        CommandKeyExtractor keys = new CommandKeyExtractor();
        keys.preloadInBackground(() -> backendClients.getClient(0));
        Backend backend = new Backend(keys);
        WatchSessions watches = new WatchSessions(watchPool);
        CatchAllCommand catchAll = new CatchAllCommand(backendClients, backend, keys,
                props.glide().coalesceRequests() ? new Coalescer(backendClients, backend) : null,
                new BlockingCommandRunner(blockingPool, backend),
                new TransactionQueue(backendClients, keys));
        return new CommandTable(catchAll)
                .listener(metrics)
                .rejectInTransaction(request -> {
                    // Answered from proxy state (INFO, HELLO, SCAN, CLUSTER, PROXY), which a node
                    // can't reproduce inside EXEC. Like any queue-time error, this discards the transaction.
                    return TransactionQueue.reject(request.connection(), "ERR Command '"
                            + request.command().toLowerCase(Locale.ROOT)
                            + "' is not supported inside a transaction by this proxy");
                })
                .register("ping", new PingCommand())
                .register("echo", new EchoCommand())
                .register("time", new TimeCommand())
                .register("quit", new QuitCommand())
                .register("select", new SelectCommand(watches))
                .register("multi", new MultiCommand())
                .register("exec", new ExecCommand(backendClients, watches, props.transactions().crossSlot()))
                .register("discard", new DiscardCommand(watches))
                .register("watch", new WatchCommand(watches))
                .register("unwatch", new WatchCommand(watches))
                .register("scan", new ScanCommand(backendClients, scanCursorStore))
                .register("proxy", new ValkeywayAdminCommand(backendClients))
                .register("hello", new HelloCommand())
                .register("info", new InfoCommand(metrics))
                .register("cluster", new ClusterCommand(backendClients, backend))
                .register("client", new ClientCommand(backendClients, backend))
                .register("command", new CommandCommand(backendClients));
    }
}
