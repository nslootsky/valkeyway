/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A reply to a client, in protocol-neutral form. {@link ReplyEncoder} writes it as RESP3, or as RESP2
 * with the RESP3-only types (map, set, double, boolean, big number, verbatim string) mapped the way a
 * RESP2 server maps them. Where Valkey's two shapes differ in structure (WITHSCORES results, XREAD),
 * a dedicated type carries both.
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

    /** A double already formatted (geo coordinates): RESP3 {@code ,text}, RESP2 bulk {@code text}. */
    record FormattedDouble(String text) implements Reply {}

    /** RESP3 {@code =} with format {@code txt} (INFO, CLIENT LIST, ...). RESP2: bulk string. */
    record Verbatim(String text) implements Reply {}

    /**
     * RESP3: an array of two-element arrays, {@code [[a, b], [c, d]]}. RESP2: flat, {@code [a, b, c, d]}.
     * (WITHSCORES and WITHVALUES results.) {@code items} is flat and of even length.
     */
    record FlatPairs(List<Reply> items) implements Reply {}

    /**
     * RESP3: a map. RESP2: an array of two-element arrays, {@code [[k1, v1], [k2, v2]]} (XREAD).
     */
    record NestedMap(List<Reply> keysAndValues) implements Reply {}
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
