/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.ops.MetricsCollector;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.InlineCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * INFO: proxy-specific info with role:master, compatible with Glide's standalone mode detection.
 */
public class InfoCommand implements InlineCommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    private final MetricsCollector metrics;

    public InfoCommand(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public Reply execute(Request request) {
        String info = "# Replication\r\n"
                + "role:master\r\n"
                + "connected_slaves:0\r\n"
                + "# Stats\r\n"
                + "total_commands_processed:" + (long) metrics.getTotalCommands() + "\r\n"
                + "total_errors:" + (long) metrics.getTotalErrors() + "\r\n";
        return Reply.bulk(info);
    }
}
