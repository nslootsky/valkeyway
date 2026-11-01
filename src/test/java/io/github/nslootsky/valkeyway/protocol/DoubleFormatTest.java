/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoubleFormatTest {

    /**
     * Expected strings are what Valkey 9.1 returns for ZSCORE over RESP2.
     */
    @ParameterizedTest
    @CsvSource({
            "1.5, 1.5",
            "2, 2",
            "0.1, 0.1",
            "1e20, 1e+20",
            "1e-7, 1e-7",
            "-0.5, -0.5",
            "123456789012345678, 123456789012345680",
            "3.14159265358979, 3.14159265358979",
            "1e17, 100000000000000000",
            "1234567.125, 1234567.125",
            "0, 0",
            "-0.0, 0",
            "Infinity, inf",
            "-Infinity, -inf",
    })
    void doublesFormatLikeValkey(double value, String expected) {
        assertEquals(expected, DoubleFormat.valkey(value));
    }
}
