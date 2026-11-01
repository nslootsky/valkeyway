/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A reply to a client, in protocol-neutral form. {@link ReplyEncoder} writes it as RESP2, mapping the
 * RESP3-only types (map, set, double, boolean, big number) the way a RESP2 server would.
 */
public sealed interface Reply {

    /** {@code +value}. Must not contain CR or LF. */
    record Simple(String value) implements Reply {}

    /** {@code -message}, conventionally "CODE detail". */
    record Error(String message) implements Reply {}

    /** {@code :value}, full 64-bit range. */
    record Int(long value) implements Reply {}

    /** {@code $len value}, binary-safe. */
    record Bulk(byte[] value) implements Reply {
        @Override
        public boolean equals(Object o) {
            return o instanceof Bulk(byte[] other) && Arrays.equals(value, other);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Bulk[" + new String(value, StandardCharsets.UTF_8) + "]";
        }
    }

    /** {@code *n ...}. */
    record Array(List<Reply> items) implements Reply {}

    /** RESP3 {@code %}: alternating keys and values. RESP2: flat array. */
    record MapReply(List<Reply> keysAndValues) implements Reply {}

    /** RESP3 {@code ~}. RESP2: array. */
    record SetReply(List<Reply> items) implements Reply {}

    /** RESP3 {@code ,}. RESP2: bulk string in Valkey's double format. */
    record Double(double value) implements Reply {}

    /** RESP3 {@code #}. RESP2: integer 1/0. */
    record Bool(boolean value) implements Reply {}

    /** RESP3 {@code (}. RESP2: bulk string. */
    record BigNumber(String digits) implements Reply {}

    /** The two RESP2 nulls; RESP3 has a single null ({@code _}) for both. */
    enum Null implements Reply {
        /** {@code $-1}: a missing value. */
        BULK,
        /** {@code *-1}: a missing array (timeouts, aborted EXEC, ...). */
        ARRAY
    }

    Reply OK = new Simple("OK");
    Reply QUEUED = new Simple("QUEUED");
    Reply PONG = new Simple("PONG");
    Reply EMPTY_ARRAY = new Array(List.of());
    Reply ZERO = new Int(0);
    Reply ONE = new Int(1);

    static Reply bulk(String value) {
        return new Bulk(value.getBytes(StandardCharsets.UTF_8));
    }

    static Reply array(Reply... items) {
        return new Array(List.of(items));
    }

    static Reply error(String message) {
        return new Error(message);
    }

    static Reply integer(long value) {
        return value == 0 ? ZERO : value == 1 ? ONE : new Int(value);
    }
}
