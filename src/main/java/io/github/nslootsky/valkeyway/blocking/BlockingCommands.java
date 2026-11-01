/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises commands that can block their connection on the server, and where their timeout is.
 *
 * <p>Such commands must not be sent over Glide's shared, multiplexed connection: the server stops
 * serving a connection while one of its commands is blocked, so every other client's commands to
 * that node would wait (and time out) behind it.
 *
 * <p>Relies on upstream behavior: [upstream:V5] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class BlockingCommands {

    /** How a command's timeout argument is expressed. */
    public enum TimeoutUnit { SECONDS, MILLISECONDS }

    /** What the command replies with when it times out. */
    public enum TimeoutReply { NULL, ZERO, ZERO_PAIR }

    /**
     * @param timeoutIndex  index in args of the timeout argument
     * @param timeoutMillis the timeout in milliseconds; 0 means wait forever
     * @param error         Valkey's error for an invalid timeout, or {@code null}. Checked by the proxy,
     *                      since Glide parses blocking commands' timeouts itself and rejects bad ones
     *                      with its own wording. [upstream:V9]
     */
    public record Spec(int timeoutIndex, TimeoutUnit unit, long timeoutMillis, TimeoutReply timeoutReply, String error) {

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
     * command cannot block. A malformed timeout gives a spec with Valkey's error for it.
     */
    public static Optional<Spec> detect(byte[][] args) {
        if (args.length < 1 || args[0].length == 0) {
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
                    Optional.of(seconds(args, last));
            case "BLMPOP", "BZMPOP" ->
                    Optional.of(seconds(args, 1));
            case "XREAD", "XREADGROUP" -> args.length > 1 ? blockOption(args) : Optional.empty();
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

    /** Decimal numbers as Valkey's string2ld accepts them (no hex, no Java suffixes like "1d"). */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern INFINITY = Pattern.compile("[+-]?inf(inity)?", Pattern.CASE_INSENSITIVE);
    /** C's hexadecimal floating point, which strtold accepts too: 0x1A, 0x1.8p3. */
    private static final Pattern HEX = Pattern.compile("[+-]?0[xX]([0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)([pP][+-]?\\d+)?");

    /**
     * A timeout in seconds; such commands (BLPOP & co.) answer a null array when it expires. Valkey's
     * getTimeoutFromObjectOrReply: a long double, at most LLONG_MAX milliseconds, not negative.
     */
    // [upstream:V9]
    private static Spec seconds(byte[][] args, int index) {
        if (index < 1 || index >= args.length) {
            return new Spec(index, TimeoutUnit.SECONDS, 0, TimeoutReply.NULL, null); // arity is checked separately
        }
        String text = new String(args[index], StandardCharsets.US_ASCII);
        double seconds;
        if (DECIMAL.matcher(text).matches()) {
            seconds = Double.parseDouble(text);
        } else if (HEX.matcher(text).matches()) {
            seconds = Double.parseDouble(text.matches(".*[pP].*") ? text : text + "p0");
        } else if (INFINITY.matcher(text).matches()) {
            seconds = text.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        } else {
            return invalid(index, TimeoutUnit.SECONDS, TimeoutReply.NULL, "ERR timeout is not a float or out of range");
        }
        // Valkey adds the current time, in milliseconds, and stores the deadline in a long long.
        if (seconds * 1000 + System.currentTimeMillis() > Long.MAX_VALUE) {
            return invalid(index, TimeoutUnit.SECONDS, TimeoutReply.NULL, "ERR timeout is out of range");
        }
        if (seconds < 0) {
            return invalid(index, TimeoutUnit.SECONDS, TimeoutReply.NULL, "ERR timeout is negative");
        }
        long millis = seconds > 0 ? Math.max(1, (long) Math.ceil(seconds * 1000)) : 0;
        return new Spec(index, TimeoutUnit.SECONDS, millis, TimeoutReply.NULL, null);
    }

    /** A timeout in milliseconds (XREAD BLOCK, WAIT, WAITAOF): a long long, not negative. */
    // [upstream:V9]
    private static Spec millis(byte[][] args, int index, TimeoutReply reply) {
        long millis;
        try {
            millis = Long.parseLong(new String(args[index], StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            return invalid(index, TimeoutUnit.MILLISECONDS, reply, "ERR timeout is not an integer or out of range");
        }
        if (millis < 0) {
            return invalid(index, TimeoutUnit.MILLISECONDS, reply, "ERR timeout is negative");
        }
        return new Spec(index, TimeoutUnit.MILLISECONDS, millis, reply, null);
    }

    private static Spec invalid(int index, TimeoutUnit unit, TimeoutReply reply, String error) {
        return new Spec(index, unit, 0, reply, error);
    }
}
