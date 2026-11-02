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

    private static Map<Object, Object> map(Object... kv) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void searchResultsBecomeKeyFieldArrays() {
        Object glide = new Object[]{2L, map(g("doc:1"), map(g("color"), g("red"), g("n"), g("1")), g("doc:2"), map())};
        Object[] shaped = (Object[]) ReplyShapes.shape(args("FT.SEARCH idx * RETURN 2 color n"), glide);
        assertEquals(5, shaped.length);
        assertEquals(2L, shaped[0]);
        assertEquals(g("doc:1"), shaped[1]);
        assertArrayEquals(new Object[]{g("color"), g("red"), g("n"), g("1")}, (Object[]) shaped[2]);
        assertEquals(g("doc:2"), shaped[3]);
        assertArrayEquals(new Object[0], (Object[]) shaped[4], "a document without fields keeps an empty list");
    }

    @Test
    void searchWithoutContentListsKeysOnly() {
        Object glide = new Object[]{2L, map(g("doc:1"), map(), g("doc:2"), map())};
        assertArrayEquals(new Object[]{2L, g("doc:1"), g("doc:2")},
                (Object[]) ReplyShapes.shape(args("FT.SEARCH idx * NOCONTENT"), glide));
    }

    @Test
    void searchTotalsAndEmptyResultsKeepTheirShape() {
        Object total = new Object[]{3L};
        assertSame(total, ReplyShapes.shape(args("FT.SEARCH idx * LIMIT 0 0"), total));
        assertArrayEquals(new Object[]{0L}, (Object[]) ReplyShapes.shape(args("FT.SEARCH idx nothing"),
                new Object[]{0L, map()}));
    }

    @Test
    void aggregateRowsGetTheirCountBack() {
        Object glide = new Object[]{map(g("color"), g("red")), map(g("color"), g("blue"))};
        Object[] shaped = (Object[]) ReplyShapes.shape(args("ft.aggregate idx * LOAD 1 @color"), glide);
        assertEquals(2L, shaped[0]);
        assertArrayEquals(new Object[]{g("color"), g("red")}, (Object[]) shaped[1]);
        assertArrayEquals(new Object[]{g("color"), g("blue")}, (Object[]) shaped[2]);
    }

    @Test
    void indexInfoIsFlattenedAtEveryLevel() {
        Object glide = map(g("index_name"), g("idx"),
                g("index_definition"), map(g("key_type"), g("HASH"), g("prefixes"), new Object[]{g("doc:")}),
                g("num_docs"), 2L);
        Object[] shaped = (Object[]) ReplyShapes.shape(args("FT.INFO idx"), glide);
        assertEquals(6, shaped.length);
        assertEquals(g("index_definition"), shaped[2]);
        Object[] definition = (Object[]) shaped[3];
        assertEquals(g("key_type"), definition[0]);
        assertArrayEquals(new Object[]{g("doc:")}, (Object[]) definition[3]);
        assertEquals(2L, shaped[5]);
    }
}
