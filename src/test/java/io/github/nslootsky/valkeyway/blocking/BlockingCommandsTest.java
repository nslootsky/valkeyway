/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import io.github.nslootsky.valkeyway.blocking.BlockingCommands.Spec;
import io.github.nslootsky.valkeyway.blocking.BlockingCommands.TimeoutReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BlockingCommandsTest {

    private static byte[][] args(String line) {
        return Arrays.stream(line.split(" ")).map(s -> s.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
    }

    private static Spec spec(String line) {
        return BlockingCommands.detect(args(line)).orElseThrow(() -> new AssertionError("not detected: " + line));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "BLPOP a b 1.5                              | 3 | 1500",
            "brpop a 0                                  | 2 | 0",
            "BZPOPMIN z 2                               | 2 | 2000",
            "BZPOPMAX z 0.001                           | 2 | 1",
            "BRPOPLPUSH src dst 3                       | 3 | 3000",
            "BLMOVE src dst LEFT RIGHT 0.25             | 5 | 250",
            "BLMPOP 2 2 a b LEFT                        | 1 | 2000",
            "BZMPOP 0 1 z MIN COUNT 2                   | 1 | 0",
            "XREAD COUNT 10 BLOCK 500 STREAMS s $       | 4 | 500",
            "XREADGROUP GROUP g c BLOCK 0 STREAMS s >   | 5 | 0",
            "WAIT 1 250                                 | 2 | 250",
            "WAITAOF 1 0 100                            | 3 | 100",
    })
    void findsTimeout(String line, int index, long millis) {
        Spec spec = spec(line);
        assertEquals(index, spec.timeoutIndex());
        assertEquals(millis, spec.timeoutMillis());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET k",
            "LPOP list",
            "XREAD COUNT 10 STREAMS s $",
            "XREAD STREAMS BLOCK 0",          // a key named BLOCK after STREAMS is not the option
            "WAIT 1",
            "BLPOP",
    })
    void ignoresNonBlockingCommands(String line) {
        assertTrue(BlockingCommands.detect(args(line)).isEmpty(), line);
    }

    @Test
    void malformedTimeoutIsTreatedAsForever() {
        assertTrue(spec("BLPOP k notanumber").waitsForever());
        assertTrue(spec("BLPOP k -1").waitsForever());
        assertTrue(spec("XREAD BLOCK x STREAMS s $").waitsForever());
    }

    @Test
    void timeoutReplies() {
        assertEquals(TimeoutReply.NULL, spec("BLPOP k 1").timeoutReply());
        assertEquals(TimeoutReply.NULL, spec("XREAD BLOCK 1 STREAMS s $").timeoutReply());
        assertEquals(TimeoutReply.ZERO, spec("WAIT 1 1").timeoutReply());
        assertEquals(TimeoutReply.ZERO_PAIR, spec("WAITAOF 1 0 1").timeoutReply());
    }

    private static String rewritten(String line, long elapsed) {
        Spec spec = spec(line);
        return new String(spec.withRemainingTimeout(args(line), elapsed)[spec.timeoutIndex()], StandardCharsets.UTF_8);
    }

    @Test
    void remainingTimeoutIsReducedByWaitTime() {
        assertEquals("1.000", rewritten("BLPOP k 1.5", 500));
        assertEquals("700", rewritten("XREAD BLOCK 1000 STREAMS s $", 300));
    }

    @Test
    void remainingTimeoutNeverDropsToZeroSoTheCommandStillRunsOnce() {
        // 0 would mean "block forever"; the smallest positive timeout still returns available data.
        assertEquals("0.001", rewritten("BLPOP k 1", 5000));
        assertEquals("1", rewritten("WAIT 1 100", 5000));
    }

    @Test
    void infiniteTimeoutAndNoWaitAreLeftAlone() {
        byte[][] forever = args("BLPOP k 0");
        assertSame(forever, spec("BLPOP k 0").withRemainingTimeout(forever, 1000));
        byte[][] noWait = args("BLPOP k 2");
        assertSame(noWait, spec("BLPOP k 2").withRemainingTimeout(noWait, 0));
    }

    @Test
    void rewriteDoesNotModifyTheOriginalArgs() {
        byte[][] original = args("BLPOP k 2");
        spec("BLPOP k 2").withRemainingTimeout(original, 500);
        assertEquals("2", new String(original[2], StandardCharsets.UTF_8));
    }
}
