/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler;

import com.github.tonivade.resp.command.CommandSuite;
import com.github.tonivade.resp.command.RespCommand;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.commands.*;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import io.github.nslootsky.valkeyway.scan.ScanCursorStore;

/**
 * Command router for the proxy. Registers named handlers for supported commands
 * and falls back to CatchAllCommand for all others.
 */
public class ValkeywayCommandSuite extends CommandSuite {

    private final CatchAllCommand catchAllCommand;

    public ValkeywayCommandSuite(GlideClientCache glideClientCache, ScanCursorStore scanCursorStore, MetricsCollector metrics) {
        super();
        this.catchAllCommand = new CatchAllCommand(glideClientCache, metrics);

        addCommand("select", new SelectCommand());
        addCommand("multi", new MultiCommand());
        addCommand("exec", new ExecCommand(glideClientCache));
        addCommand("discard", new DiscardCommand());
        addCommand("scan", new ScanCommand(glideClientCache, scanCursorStore));
        addCommand("proxy", new ValkeywayAdminCommand(glideClientCache));
        addCommand("hello", new HelloCommand());
        addCommand("info", new InfoCommand(metrics));
        addCommand("cluster", new ClusterCommand());
    }

    @Override
    public RespCommand getCommand(String name) {
        RespCommand command = super.getCommand(name);
        if (command != null && command.getClass().getSimpleName().equals("NullCommand")) {
            return catchAllCommand;
        }
        return command;
    }
}
