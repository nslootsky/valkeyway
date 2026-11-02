/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchListCommandTest {

    private static Reply b(String s) {
        return Reply.bulk(s);
    }

    @Test
    void eachNameIsListedOnceInFirstSeenOrder() {
        // Three primaries in coordinator mode each list both indexes.
        Reply joined = Reply.array(b("idx:a"), b("idx:b"), b("idx:a"), b("idx:b"), b("idx:b"), b("idx:a"));
        assertEquals(Reply.array(b("idx:a"), b("idx:b")), SearchListCommand.distinct(joined));
    }

    @Test
    void resp3SetsAreDeduplicatedToo() {
        Reply joined = new Reply.SetReply(List.of(b("idx:a"), b("idx:a")));
        assertEquals(new Reply.SetReply(List.of(b("idx:a"))), SearchListCommand.distinct(joined));
    }

    @Test
    void namesAreComparedAsBytes() {
        byte[] raw = {(byte) 0xff, 'x'};
        Reply joined = Reply.array(new Reply.Bulk(raw), new Reply.Bulk(raw.clone()), b("ÿx"));
        Reply distinct = SearchListCommand.distinct(joined);
        assertEquals(2, ((Reply.Array) distinct).items().size(), "0xff 'x' and the UTF-8 for ÿ 'x' differ");
        assertArrayEquals("ÿx".getBytes(StandardCharsets.UTF_8),
                ((Reply.Bulk) ((Reply.Array) distinct).items().get(1)).value());
    }

    @Test
    void otherRepliesPassThrough() {
        Reply error = Reply.error("ERR unknown command 'FT._LIST'");
        assertSame(error, SearchListCommand.distinct(error));
        Reply empty = Reply.array();
        assertEquals(empty, SearchListCommand.distinct(empty));
    }
}
