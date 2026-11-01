/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Expected values were taken from CLUSTER KEYSLOT on Valkey 9.1.
 */
class ClusterSlotsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "foo                  | 12182",
            "bar                  | 5061",
            "{user1000}.following | 3443",
            "{user1000}.followers | 3443",
            "{}foo                | 9500",
            "foo{}{bar}           | 8363",
            "foo{{bar}}zap        | 4015",
            "a{b                  | 13340",
    })
    void matchesServerKeyslot(String key, int expectedSlot) {
        assertEquals(expectedSlot, ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({"''"})
    void emptyKeyIsSlotZero(String key) {
        assertEquals(0, ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8)));
    }
}
