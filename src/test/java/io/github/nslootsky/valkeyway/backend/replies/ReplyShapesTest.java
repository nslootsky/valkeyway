/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.replies;

import io.github.nslootsky.valkeyway.protocol.DoubleFormat;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.protocol.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for reshaping; the differential integration test checks real server output.
 */
class ReplyShapesTest {

    private static byte[][] args(String line) {
        return Arrays.stream(line.split(" ")).map(s -> s.getBytes(StandardCharsets.US_ASCII)).toArray(byte[][]::new);
    }

    private static GlideString g(String s) {
        return GlideString.of(s);
    }

    @ParameterizedTest
    @CsvSource({
            "56.4413, 4, false, 56.4413",
            "100.5, 4, false, 100.5000",
            "0, 4, false, 0.0000",
            "15.0, 17, true, 15",
            "13.361389338970184, 17, true, 13.36138933897018433",
            "-0.0, 17, true, 0",
            "-12.5, 17, true, -12.5",
    })
    void fixedFormattingMatchesValkey(double value, int decimals, boolean trim, String expected) {
        assertEquals(expected, DoubleFormat.fixed(value, decimals, trim));
    }

    @Test
    void streamEntriesBecomeIdFieldArrays() {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put(g("1-1"), new Object[]{new Object[]{g("f"), g("v")}, new Object[]{g("g"), g("w")}});
        entries.put(g("1-2"), null); // deleted entry still pending in a group

        Object[] shaped = (Object[]) ReplyShapes.shape(args("XRANGE s - +"), entries);

        assertArrayEquals(new Object[]{g("1-1"), new Object[]{g("f"), g("v"), g("g"), g("w")}}, (Object[]) shaped[0]);
        assertArrayEquals(new Object[]{g("1-2"), Reply.Null.ARRAY}, (Object[]) shaped[1], "deleted entry: *-1 fields");
    }

    @Test
    void justIdRepliesAreLeftAlone() {
        Object[] ids = {g("1-1"), g("1-2")};
        assertSame(ids, ReplyShapes.shape(args("XCLAIM s g c 0 1-1 1-2 JUSTID"), ids));
    }

    @Test
    void pairsAreFlattenedOnlyWithTheirOption() {
        Object[] pairs = {new Object[]{g("m"), 1.5}, new Object[]{g("n"), 2.0}};
        assertEquals(new Reply.FlatPairs(List.of(Reply.bulk("m"), new Reply.Double(1.5), Reply.bulk("n"), new Reply.Double(2.0))),
                ReplyShapes.shape(args("ZRANDMEMBER z -2 WITHSCORES"), pairs));
        assertSame(pairs, ReplyShapes.shape(args("ZRANDMEMBER z -2"), pairs));
    }

    @Test
    void unexpectedShapesPassThroughUnchanged() {
        Object scalar = g("surprise");
        assertSame(scalar, ReplyShapes.shape(args("XRANGE s - +"), scalar));
        Object[] mixed = {new Object[]{g("a"), g("b")}, g("not a pair")};
        assertSame(mixed, ReplyShapes.shape(args("HRANDFIELD h -2 WITHVALUES"), mixed));
        assertEquals(Reply.Null.ARRAY, ReplyShapes.shape(args("XREAD STREAMS s 0"), null), "no data: *-1");
        assertNull(ReplyShapes.shape(args("GET k"), null), "missing value: $-1");
    }

    @Test
    void unrelatedCommandsAreNotTouched() {
        Object value = new LinkedHashMap<>(Map.of(g("f"), g("v")));
        assertSame(value, ReplyShapes.shape(args("HGETALL h"), value));
        assertSame(value, ReplyShapes.shape(args("GET k"), value));
    }
}
