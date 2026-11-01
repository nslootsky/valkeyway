/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NodeAdminCommandsTest {

    private static byte[][] args(String command) {
        return Arrays.stream(command.split(" ")).map(s -> s.getBytes(StandardCharsets.US_ASCII)).toArray(byte[][]::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHUTDOWN", "shutdown NOSAVE", "REPLICAOF host 6379", "SLAVEOF NO ONE", "FAILOVER",
            "DEBUG SLEEP 1", "SAVE", "BGSAVE", "BGREWRITEAOF", "MODULE LOAD x", "CONFIG SET maxmemory 1",
            "config rewrite", "CONFIG RESETSTAT", "ACL SETUSER bob", "CLUSTER MEET 1.2.3.4 6379", "CLUSTER RESET",
            "cluster setslot 1 node x", "CLUSTER FORGET id"})
    void refused(String command) {
        String error = new NodeAdminCommands(true).check(args(command));
        assertNotNull(error, command);
        assertTrue(error.startsWith("ERR ") && error.contains("administers a single node"), error);
        assertNull(new NodeAdminCommands(false).check(args(command)), "forward mode lets it through");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET k", "SET k v", "CONFIG GET maxmemory", "CONFIG", "ACL WHOAMI", "ACL LIST",
            "CLUSTER KEYSLOT k", "CLUSTER INFO", "SCAN 0", "SCRIPT FLUSH", "FLUSHALL", "PING", "DEBUGX", "S"})
    void allowed(String command) {
        assertNull(new NodeAdminCommands(true).check(args(command)), command);
    }
}
