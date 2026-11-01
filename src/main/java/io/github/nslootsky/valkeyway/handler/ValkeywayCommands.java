/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler;

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
                                      boolean coalesceRequests) {
        CatchAllCommand catchAll = new CatchAllCommand(glideClientCache, metrics, blockingPool, coalesceRequests);
        catchAll.warmUp();
        return new CommandTable(catchAll)
                .register("ping", new PingCommand())
                .register("echo", new EchoCommand())
                .register("time", new TimeCommand())
                .register("quit", new QuitCommand())
                .register("select", new SelectCommand())
                .register("multi", new MultiCommand())
                .register("exec", new ExecCommand(glideClientCache))
                .register("discard", new DiscardCommand())
                .register("scan", new ScanCommand(glideClientCache, scanCursorStore))
                .register("proxy", new ValkeywayAdminCommand(glideClientCache))
                .register("hello", new HelloCommand())
                .register("info", new InfoCommand(metrics))
                .register("cluster", new ClusterCommand(glideClientCache))
                .register("command", new CommandCommand(glideClientCache));
    }
}
