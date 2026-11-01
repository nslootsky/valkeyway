/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cases from Valkey's own pattern tests (keyspace.tcl, pubsub.tcl) and stringmatchlen's edge cases. */
class GlobTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "*            | anything     | true",
            "*            | ''           | false",
            "foo*         | foo          | true",
            "foo*         | foobar       | true",
            "foo*         | fo           | false",
            "*.foo        | a.foo        | true",
            "h?llo        | hello        | true",
            "h?llo        | hllo         | false",
            "h*llo        | heeeello     | true",
            "h[ae]llo     | hallo        | true",
            "h[ae]llo     | hillo        | false",
            "h[^e]llo     | hallo        | true",
            "h[^e]llo     | hello        | false",
            "h[a-b]llo    | hbllo        | true",
            "h[b-a]llo    | hallo        | true",
            "h[a-b]llo    | hcllo        | false",
            "h\\*llo      | h*llo        | true",
            "h\\*llo      | hello        | false",
            "[\\]]        | ]            | true",
            "a**b         | axxb         | true",
            "chan.*       | chan.foo     | true",
            "chan.*       | chan         | false",
            "[abc         | a            | true",
    })
    void matchesLikeValkey(String pattern, String string, boolean expected) {
        assertEquals(expected, Glob.matches(pattern.getBytes(StandardCharsets.UTF_8), string.getBytes(StandardCharsets.UTF_8)),
                pattern + " vs " + string);
    }

    @org.junit.jupiter.api.Test
    void abusivePatternsDontBlowUp() {
        byte[] pattern = "*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b".getBytes(StandardCharsets.UTF_8);
        byte[] string = "a".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        assertEquals(false, Glob.matches(pattern, string));
    }
}
