/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Micrometer-based metrics tracking: commands processed (by type), errors, and latency.
 * Exposed via Spring Boot Actuator endpoints.
 */
@Component
public class MetricsCollector {

    private static final String PREFIX = "proxy";

    private final Counter commandsProcessed;
    private final Counter errors;
    private final Timer commandLatency;
    private final MeterRegistry meterRegistry;

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

    public void recordCommand(String commandName) {
        commandsProcessed.increment();
        Counter.builder(PREFIX + ".commands.processed")
                .tag("type", commandName.toUpperCase())
                .register(meterRegistry)
                .increment();
    }

    public void recordError() {
        errors.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(commandLatency);
    }

    public double getTotalCommands() {
        return commandsProcessed.count();
    }

    public double getTotalErrors() {
        return errors.count();
    }
}
