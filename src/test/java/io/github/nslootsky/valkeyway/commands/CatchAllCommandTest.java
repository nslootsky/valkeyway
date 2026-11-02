/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CatchAllCommandTest {

    private static byte[][] args(String name) {
        return new byte[][]{name.getBytes(StandardCharsets.US_ASCII), "x".getBytes(StandardCharsets.US_ASCII)};
    }

    @ParameterizedTest
    @ValueSource(strings = {"KEYS", "eval", "EVALSHA_RO", "fcall", "FUNCTION", "script", "SORT", "sort_ro",
            "FT.SEARCH", "ft.aggregate", "FT.PROFILE"})
    void slowCommandsAreNotCoalesced(String name) {
        assertTrue(CatchAllCommand.mayRunLong(args(name)), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "set", "HGETALL", "JSON.GET", "BF.ADD", "FT.INFO", "FT._LIST", "FT.CREATE",
            "ft.aggregatex", "K"})
    void quickCommandsAre(String name) {
        assertFalse(CatchAllCommand.mayRunLong(args(name)), name);
    }
}
