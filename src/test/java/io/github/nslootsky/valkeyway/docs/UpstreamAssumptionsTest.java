/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Keeps docs/UPSTREAM_ASSUMPTIONS.md and the {@code [upstream:ID]} tags in the code in step: every
 * listed assumption is tagged where the code relies on it, and every tag names a listed assumption.
 */
class UpstreamAssumptionsTest {

    private static final Path CATALOG = Path.of("docs/UPSTREAM_ASSUMPTIONS.md");
    private static final Pattern HEADING = Pattern.compile("^### ([GV]\\d+): ", Pattern.MULTILINE);
    private static final Pattern TAG = Pattern.compile("\\[upstream:([A-Z]\\d+)]");

    @Test
    void catalogAndCodeTagsMatch() throws IOException {
        Set<String> listed = new TreeSet<>();
        Matcher heading = HEADING.matcher(Files.readString(CATALOG));
        while (heading.find()) {
            listed.add(heading.group(1));
        }
        assertFalse(listed.isEmpty(), "no entries found in " + CATALOG);

        Set<String> taggedInMain = tags(Path.of("src/main/java"));
        Set<String> taggedAnywhere = new TreeSet<>(taggedInMain);
        taggedAnywhere.addAll(tags(Path.of("src/test/java")));

        Set<String> untagged = new TreeSet<>(listed);
        untagged.removeAll(taggedInMain);
        assertEquals(Set.of(), untagged, "assumptions in " + CATALOG + " that no code in src/main is tagged with");

        Set<String> unknown = new TreeSet<>(taggedAnywhere);
        unknown.removeAll(listed);
        assertEquals(Set.of(), unknown, "[upstream:...] tags with no entry in " + CATALOG);
    }

    private static Set<String> tags(Path root) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher tag = TAG.matcher(Files.readString(file));
                while (tag.find()) {
                    found.add(tag.group(1));
                }
            }
        }
        return found;
    }
}
