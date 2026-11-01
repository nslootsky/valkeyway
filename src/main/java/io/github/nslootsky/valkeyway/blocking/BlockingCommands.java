/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Recognises commands that can block their connection on the server, and where their timeout is.
 *
 * <p>Such commands must not be sent over Glide's shared, multiplexed connection: the server stops
 * serving a connection while one of its commands is blocked, so every other client's commands to
 * that node would wait (and time out) behind it.
 */
public final class BlockingCommands {

    /** How a command's timeout argument is expressed. */
    public enum TimeoutUnit { SECONDS, MILLISECONDS }

    /** What the command replies with when it times out. */
    public enum TimeoutReply { NULL, ZERO, ZERO_PAIR }

    /**
     * @param timeoutIndex  index in args of the timeout argument
     * @param timeoutMillis the timeout in milliseconds; 0 means wait forever
     */
    public record Spec(int timeoutIndex, TimeoutUnit unit, long timeoutMillis, TimeoutReply timeoutReply) {

        public boolean waitsForever() {
            return timeoutMillis == 0;
        }

        /**
         * Returns a copy of {@code args} with the timeout reduced by {@code elapsedMillis}, so time spent
         * waiting for a pooled client counts against the command's own timeout. Never rewrites an
         * infinite timeout, and never goes below the smallest positive timeout: the command must still
         * run once, because it returns available data immediately regardless of its timeout.
         */
        public byte[][] withRemainingTimeout(byte[][] args, long elapsedMillis) {
            if (waitsForever() || elapsedMillis <= 0) {
                return args;
            }
            long remaining = Math.max(timeoutMillis - elapsedMillis, 1);
            String value = unit == TimeoutUnit.SECONDS
                    ? String.format(Locale.ROOT, "%.3f", remaining / 1000.0)
                    : Long.toString(remaining);
            byte[][] copy = args.clone();
            copy[timeoutIndex] = value.getBytes(StandardCharsets.US_ASCII);
            return copy;
        }
    }

    private BlockingCommands() {}

    /**
     * Returns the blocking spec for {@code args} (args[0] is the command name), or empty if the
     * command cannot block. Malformed timeouts are treated as "wait forever" here; the server
     * reports the actual error.
     */
    public static Optional<Spec> detect(byte[][] args) {
        if (args.length < 2 || args[0].length == 0) {
            return Optional.empty();
        }
        // Fast path for the hot loop: every blocking command starts with B, X or W.
        switch (args[0][0] | 0x20) {
            case 'b', 'x', 'w' -> { }
            default -> {
                return Optional.empty();
            }
        }
        String name = new String(args[0], StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        int last = args.length - 1;
        return switch (name) {
            case "BLPOP", "BRPOP", "BZPOPMIN", "BZPOPMAX", "BRPOPLPUSH", "BLMOVE" ->
                    Optional.of(seconds(args, last, TimeoutReply.NULL));
            case "BLMPOP", "BZMPOP" ->
                    Optional.of(seconds(args, 1, TimeoutReply.NULL));
            case "XREAD", "XREADGROUP" -> blockOption(args);
            case "WAIT" -> args.length > 2
                    ? Optional.of(millis(args, 2, TimeoutReply.ZERO))
                    : Optional.empty();
            case "WAITAOF" -> args.length > 3
                    ? Optional.of(millis(args, 3, TimeoutReply.ZERO_PAIR))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** XREAD / XREADGROUP only block when given BLOCK <ms>, which must come before STREAMS. */
    private static Optional<Spec> blockOption(byte[][] args) {
        for (int i = 1; i < args.length - 1; i++) {
            String opt = new String(args[i], StandardCharsets.US_ASCII);
            if (opt.equalsIgnoreCase("STREAMS")) {
                return Optional.empty();
            }
            if (opt.equalsIgnoreCase("BLOCK")) {
                return Optional.of(millis(args, i + 1, TimeoutReply.NULL));
            }
        }
        return Optional.empty();
    }

    private static Spec seconds(byte[][] args, int index, TimeoutReply reply) {
        long millis = 0;
        try {
            double seconds = Double.parseDouble(new String(args[index], StandardCharsets.US_ASCII));
            if (seconds > 0 && Double.isFinite(seconds)) {
                millis = Math.max(1, (long) Math.ceil(seconds * 1000));
            }
        } catch (NumberFormatException e) {
            // leave as "forever"; the server will reject the argument
        }
        return new Spec(index, TimeoutUnit.SECONDS, millis, reply);
    }

    private static Spec millis(byte[][] args, int index, TimeoutReply reply) {
        long millis = 0;
        try {
            millis = Math.max(0, Long.parseLong(new String(args[index], StandardCharsets.US_ASCII)));
        } catch (NumberFormatException e) {
            // leave as "forever"; the server will reject the argument
        }
        return new Spec(index, TimeoutUnit.MILLISECONDS, millis, reply);
    }
}
