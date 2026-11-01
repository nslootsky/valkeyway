/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.metrics;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Command metrics, exposed through Spring Boot Actuator: commands processed (in total and by
 * command), errors, and latency. Fed by the RESP server for every answered command, whichever
 * handler answered it.
 */
@Component
public class MetricsCollector implements CommandListener {

    private static final String PREFIX = "proxy";
    /** Distinct command names tagged individually; later ones count as OTHER, bounding the series. */
    private static final int MAX_COMMAND_TAGS = 512;

    private final MeterRegistry meterRegistry;
    private final Counter commandsProcessed;
    private final Counter errors;
    private final Timer commandLatency;
    private final ConcurrentHashMap<String, Counter> perCommand = new ConcurrentHashMap<>();

    public MetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        commandsProcessed = Counter.builder(PREFIX + ".commands.processed")
                .description("Total commands processed")
                .register(meterRegistry);
        errors = Counter.builder(PREFIX + ".errors")
                .description("Total command errors")
                .register(meterRegistry);
        commandLatency = Timer.builder(PREFIX + ".commands.latency")
                .description("Command execution latency")
                .register(meterRegistry);
    }

    @Override
    public void answered(byte[] name, Reply reply, long elapsedNanos) {
        commandsProcessed.increment();
        String command = new String(name, StandardCharsets.US_ASCII);
        Counter counter = perCommand.get(command);
        if (counter == null) {
            counter = perCommand.computeIfAbsent(command, this::commandCounter);
        }
        counter.increment();
        if (reply instanceof Reply.Error) {
            errors.increment();
        }
        commandLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private Counter commandCounter(String command) {
        String type = perCommand.size() < MAX_COMMAND_TAGS ? command.toUpperCase(Locale.ROOT) : "OTHER";
        return Counter.builder(PREFIX + ".commands.processed").tag("type", type).register(meterRegistry);
    }

    public double getTotalCommands() {
        return commandsProcessed.count();
    }

    public double getTotalErrors() {
        return errors.count();
    }
}
