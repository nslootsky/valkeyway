/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapReplyCommandsTest {

    private static byte[][] args(String line) {
        return Arrays.stream(line.split(" ")).map(s -> s.getBytes(StandardCharsets.US_ASCII)).toArray(byte[][]::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "HGETALL h", "hgetall h", "ZRANGE z 0 -1 WITHSCORES", "ZUNION 2 a b WITHSCORES", "ZINTER 2 a b",
            "ZDIFF 2 a b", "ZPOPMIN z 3", "ZPOPMAX z", "XRANGE s - +", "XREVRANGE s + -", "XCLAIM s g c 0 1-1",
            "XREAD STREAMS s 0", "XREADGROUP GROUP g c STREAMS s >", "XINFO STREAM s", "LCS a b IDX",
            "LMPOP 1 l LEFT", "BLMPOP 0 1 l RIGHT",
    })
    void mapReplyCommandsAreRouted(String line) {
        assertTrue(MapReplyCommands.mayReturnMap(args(line)), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET k", "SET k v", "ZADD z 1 m", "HGET h f", "ZRANGESTORE d z 0 -1", "XADD s * f v",
            "LPUSH l v", "ZRANGEBYSCORE z 0 1 WITHSCORES", "HGETALL", "PING", "BLPOP l 0",
    })
    void otherCommandsStayOnTheFastPath(String line) {
        assertFalse(MapReplyCommands.mayReturnMap(args(line)), line);
    }
}
