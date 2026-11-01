/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commands that administer a single server node rather than the data: through the proxy they would
 * reach whichever node Glide picks (SHUTDOWN would stop a random node, CONFIG SET change one node of
 * many). With {@code proxy.node-admin-commands=reject} (the default) they are refused; read-only
 * variants (CONFIG GET, ACL WHOAMI, CLUSTER KEYSLOT, ...) still pass.
 */
public final class NodeAdminCommands {

    /** Refused whatever their arguments. */
    private static final Set<String> COMMANDS = Set.of(
            "shutdown", "replicaof", "slaveof", "failover", "debug", "save", "bgsave", "bgrewriteaof",
            "sync", "psync", "replconf", "module",
            // Keyless and without a request policy, so Glide sends it to one node: it would swap two
            // databases on that node only.
            "swapdb");

    /** Refused for these subcommands only. */
    private static final Map<String, Set<String>> SUBCOMMANDS = Map.of(
            "config", Set.of("set", "rewrite", "resetstat"),
            "acl", Set.of("setuser", "deluser", "load", "save"),
            "cluster", Set.of("addslots", "addslotsrange", "delslots", "delslotsrange", "failover", "forget",
                    "flushslots", "meet", "replicate", "reset", "saveconfig", "set-config-epoch", "setslot",
                    "bumpepoch",
                    // Valkey 9: deleting a slot's keys, and atomic slot migration
                    "flushslot", "migrateslots", "cancelslotmigrations", "syncslots"));

    private final boolean reject;

    /** @param reject whether to refuse them; {@code false} forwards them as before */
    public NodeAdminCommands(boolean reject) {
        this.reject = reject;
    }

    /** The error to answer {@code args} with, or {@code null} if it may be sent. */
    public String check(byte[][] args) {
        if (!reject || args.length == 0 || args[0].length < 3 || args[0].length > 12) {
            return null;
        }
        // Cheap pre-check for the hot path: every name above starts with one of these letters.
        switch (args[0][0] | 0x20) {
            case 's', 'r', 'f', 'd', 'b', 'p', 'm', 'c', 'a' -> { }
            default -> {
                return null;
            }
        }
        String name = new String(args[0], StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        if (COMMANDS.contains(name)) {
            return error(name.toUpperCase(Locale.ROOT));
        }
        Set<String> subcommands = SUBCOMMANDS.get(name);
        if (subcommands != null && args.length > 1) {
            String sub = new String(args[1], StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (subcommands.contains(sub)) {
                return error((name + " " + sub).toUpperCase(Locale.ROOT));
            }
        }
        return null;
    }

    private static String error(String command) {
        return "ERR " + command + " administers a single node and is not allowed through the proxy; send it to the "
                + "node directly (or set proxy.node-admin-commands=forward)";
    }
}
