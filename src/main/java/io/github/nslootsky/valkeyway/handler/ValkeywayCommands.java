/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler;

import io.github.nslootsky.valkeyway.ValkeywayProperties;
import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.backend.Coalescer;
import io.github.nslootsky.valkeyway.blocking.BlockingCommandRunner;
import io.github.nslootsky.valkeyway.cluster.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;
import io.github.nslootsky.valkeyway.transaction.WatchClientPool;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;

import io.github.nslootsky.valkeyway.blocking.BlockingClientPool;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.commands.*;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import io.github.nslootsky.valkeyway.scan.ScanCursorStore;
import io.github.nslootsky.valkeyway.server.CommandTable;

/**
 * The proxy's command table: dedicated handlers for commands answered or adapted by the proxy,
 * and {@link CatchAllCommand} for everything forwarded to the cluster.
 */
public final class ValkeywayCommands {

    private ValkeywayCommands() {}

    public static CommandTable create(GlideClientCache glideClientCache, ScanCursorStore scanCursorStore,
                                      MetricsCollector metrics, BlockingClientPool blockingPool,
                                      WatchClientPool watchPool, ValkeywayProperties props) {
        CommandKeyExtractor keys = new CommandKeyExtractor();
        keys.preloadInBackground(glideClientCache);
        Backend backend = new Backend(keys);
        WatchSessions watches = new WatchSessions(watchPool);
        CatchAllCommand catchAll = new CatchAllCommand(glideClientCache, backend, keys,
                props.glide().coalesceRequests() ? new Coalescer(glideClientCache, backend) : null,
                new BlockingCommandRunner(blockingPool, backend),
                new TransactionQueue(glideClientCache, keys));
        return new CommandTable(catchAll)
                .listener(metrics)
                .rejectInTransaction(request -> {
                    // Answered from proxy state (INFO, HELLO, SCAN, CLUSTER, PROXY), which a node
                    // can't reproduce inside EXEC. Like any queue-time error, this discards the transaction.
                    return TransactionQueue.reject(request.connection(), "ERR Command '"
                            + request.command().toLowerCase(java.util.Locale.ROOT)
                            + "' is not supported inside a transaction by this proxy");
                })
                .register("ping", new PingCommand())
                .register("echo", new EchoCommand())
                .register("time", new TimeCommand())
                .register("quit", new QuitCommand())
                .register("select", new SelectCommand(watches))
                .register("multi", new MultiCommand())
                .register("exec", new ExecCommand(glideClientCache, watches, props.transactions().crossSlot()))
                .register("discard", new DiscardCommand(watches))
                .register("watch", new WatchCommand(watches))
                .register("unwatch", new WatchCommand(watches))
                .register("scan", new ScanCommand(glideClientCache, scanCursorStore))
                .register("proxy", new ValkeywayAdminCommand(glideClientCache))
                .register("hello", new HelloCommand())
                .register("info", new InfoCommand(metrics))
                .register("cluster", new ClusterCommand(glideClientCache, backend))
                .register("client", new ClientCommand(glideClientCache, backend))
                .register("command", new CommandCommand(glideClientCache));
    }
}
