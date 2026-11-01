/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.ops;

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
    private final Counter other;

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
        other = Counter.builder(PREFIX + ".commands.processed").tag("type", "OTHER").register(meterRegistry);
    }

    /** Longer names can't be commands; counting them as such would let clients pick metric labels. */
    static final int MAX_COMMAND_NAME = 32;

    @Override
    public void answered(byte[] name, Reply reply, long elapsedNanos) {
        commandsProcessed.increment();
        commandCounter(name).increment();
        if (reply instanceof Reply.Error) {
            errors.increment();
        }
        commandLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * The counter for a command name. Bounded: names are case-folded, at most {@link #MAX_COMMAND_TAGS}
     * distinct ones get their own counter, and anything beyond that or not shaped like a command name
     * counts as OTHER without being remembered. Clients choose the names, so an unbounded map would
     * let any client, authenticated or not, grow the heap with made-up ones.
     */
    private Counter commandCounter(byte[] name) {
        if (!looksLikeCommand(name)) {
            return other;
        }
        String command = new String(name, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        Counter counter = perCommand.get(command);
        if (counter != null) {
            return counter;
        }
        if (perCommand.size() >= MAX_COMMAND_TAGS) {
            return other;
        }
        return perCommand.computeIfAbsent(command,
                c -> Counter.builder(PREFIX + ".commands.processed").tag("type", c).register(meterRegistry));
    }

    /** Letters, digits, '.', '_' and '-' (module commands), and short. */
    static boolean looksLikeCommand(byte[] name) {
        if (name.length == 0 || name.length > MAX_COMMAND_NAME) {
            return false;
        }
        for (byte b : name) {
            boolean ok = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '.' || b == '_' || b == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public double getTotalCommands() {
        return commandsProcessed.count();
    }

    public double getTotalErrors() {
        return errors.count();
    }
}
