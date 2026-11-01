/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.cluster;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Finds the key arguments of a command, using the server's own COMMAND metadata.
 *
 * <p>The command table is loaded once from the cluster. Commands with fixed key positions
 * (first/last/step) are resolved locally; commands with movable keys (EVAL, ZUNIONSTORE,
 * XREAD, ...) or unknown to the table fall back to {@code COMMAND GETKEYS}.
 *
 * <p>Relies on upstream behavior: [upstream:V4] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public class CommandKeyExtractor {

    private static final Logger log = LoggerFactory.getLogger(CommandKeyExtractor.class);
    private static final long TIMEOUT_SECONDS = 5;

    /** @param arity the command's arity from COMMAND: n = exactly n arguments (name included), -n = at least n; 0 = unknown */
    record KeySpec(int arity, int first, int last, int step, boolean movable, boolean noMulti,
                   Map<String, KeySpec> subcommands) {
        KeySpec(int arity, int first, int last, int step, boolean movable, Map<String, KeySpec> subcommands) {
            this(arity, first, last, step, movable, false, subcommands);
        }

        KeySpec(int first, int last, int step, boolean movable, Map<String, KeySpec> subcommands) {
            this(0, first, last, step, movable, false, subcommands);
        }
    }

    private volatile Map<String, KeySpec> table;

    public CommandKeyExtractor() {}

    /** For tests: use a fixed command table instead of loading it from the cluster. */
    CommandKeyExtractor(Map<String, KeySpec> table) {
        this.table = table;
    }

    /** Whether the command table is loaded, so key lookups never block. */
    public boolean isLoaded() {
        return table != null;
    }

    /** Loads the command table now (blocking), e.g. at startup. */
    public void preload(GlideClusterClient client) {
        loadTable(client);
    }

    /** Returned by {@link #singleSlot} when a command has no keys, or keys in more than one slot. */
    public static final int NO_SLOT = -1;

    /**
     * Returns the key arguments of {@code args} (args[0] is the command name), asking the server
     * with COMMAND GETKEYS when they can't be found locally.
     */
    public List<byte[]> extractKeys(GlideClusterClient client, byte[][] args) {
        if (args.length < 2) {
            return List.of();
        }
        List<byte[]> keys = localKeys(client, args);
        return keys != null ? keys : getKeysFromServer(client, args);
    }

    /**
     * Returns the hash slot shared by all keys of {@code args}, or {@link #NO_SLOT} if the command
     * has no keys, its keys span slots, or they can't be determined without a round trip.
     */
    public int singleSlot(GlideClusterClient client, byte[][] args) {
        if (args.length < 2) {
            return NO_SLOT;
        }
        List<byte[]> keys = localKeys(client, args);
        if (keys == null || keys.isEmpty()) {
            return NO_SLOT;
        }
        int slot = ClusterSlots.slot(keys.getFirst());
        for (int i = 1; i < keys.size(); i++) {
            if (ClusterSlots.slot(keys.get(i)) != slot) {
                return NO_SLOT;
            }
        }
        return slot;
    }

    /**
     * Keys found from the command table or local parsing only, never a round trip.
     * Returns {@code null} when unknown.
     */
    private List<byte[]> localKeys(GlideClusterClient client, byte[][] args) {
        Map<String, KeySpec> specs = loadTable(client);
        if (specs == null) {
            return null;
        }
        String name = lower(args[0]);
        KeySpec spec = specs.get(name);
        if (spec == null) {
            return null;
        }
        if (!spec.subcommands().isEmpty()) {
            KeySpec sub = spec.subcommands().get(name + "|" + lower(args[1]));
            if (sub != null) {
                spec = sub;
            }
        }
        return spec.movable() ? MovableKeys.parse(name, args) : keysFromSpec(spec, args);
    }

    /**
     * The error a Valkey server sends when {@code args} is queued in a transaction but can't run:
     * an unknown command or subcommand, or the wrong number of arguments. {@code null} if it can be
     * queued, or if the command table isn't loaded. [upstream:V6]
     */
    public String queueError(GlideClusterClient client, byte[][] args) {
        Map<String, KeySpec> specs = loadTable(client);
        if (specs == null) {
            return null;
        }
        String name = lower(args[0]);
        KeySpec spec = specs.get(name);
        if (spec == null) {
            return unknownCommand(args);
        }
        String fullName = name;
        if (!spec.subcommands().isEmpty() && args.length >= 2) {
            String subName = name + "|" + lower(args[1]);
            KeySpec sub = spec.subcommands().get(subName);
            if (sub == null) {
                return "ERR unknown subcommand '" + truncate(text(args[1]), 128) + "'. Try "
                        + text(args[0]).toUpperCase(Locale.ROOT) + " HELP.";
            }
            spec = sub;
            fullName = subName;
        }
        if (spec.noMulti()) {
            return notAllowedInTransaction(fullName);
        }
        int arity = spec.arity();
        if ((arity > 0 && args.length != arity) || (arity < 0 && args.length < -arity)) {
            return "ERR wrong number of arguments for '" + fullName + "' command";
        }
        return null;
    }

    /** For commands flagged no_multi (MULTI, WATCH, SUBSCRIBE, ...), as Valkey 9 words it. [upstream:V6] */
    public static String notAllowedInTransaction(String fullName) {
        return "ERR Command '" + fullName + "' not allowed inside a transaction";
    }

    /** Valkey's processCommand: quotes arguments until the list reaches 128 characters. */
    static String unknownCommand(byte[][] args) {
        StringBuilder list = new StringBuilder();
        for (int i = 1; i < args.length && list.length() < 128; i++) {
            list.append('\'').append(truncate(text(args[i]), 128 - list.length())).append("' ");
        }
        return "ERR unknown command '" + truncate(text(args[0]), 128) + "', with args beginning with: " + list;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static List<byte[]> keysFromSpec(KeySpec spec, byte[][] args) {
        if (spec.first() <= 0) {
            return List.of();
        }
        int last = spec.last() < 0 ? args.length + spec.last() : spec.last();
        last = Math.min(last, args.length - 1);
        int step = Math.max(spec.step(), 1);
        List<byte[]> keys = new ArrayList<>();
        for (int i = spec.first(); i <= last; i += step) {
            keys.add(args[i]);
        }
        return keys;
    }

    private Map<String, KeySpec> loadTable(GlideClusterClient client) {
        Map<String, KeySpec> loaded = table;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (table == null) {
                try {
                    ClusterValue<Object> result = client.customCommand(new GlideString[]{GlideString.of("COMMAND")})
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    table = parseTable(asList(result.getSingleValue()));
                    log.info("Loaded key specs for {} commands", table.size());
                } catch (Exception e) {
                    // Leave the table unset so the next call retries; GETKEYS covers us meanwhile.
                    log.warn("Failed to load COMMAND table, falling back to COMMAND GETKEYS: {}", e.getMessage());
                    return null;
                }
            }
            return table;
        }
    }

    static Map<String, KeySpec> parseTable(List<Object> entries) {
        Map<String, KeySpec> specs = new HashMap<>();
        for (Object entry : entries) {
            List<Object> info = asList(entry);
            if (info.size() >= 6) {
                specs.put(str(info.get(0)).toLowerCase(Locale.ROOT), parseSpec(info));
            }
        }
        return specs;
    }

    private static KeySpec parseSpec(List<Object> info) {
        boolean movable = asList(info.get(2)).stream().anyMatch(f -> "movablekeys".equalsIgnoreCase(str(f)));
        boolean noMulti = asList(info.get(2)).stream().anyMatch(f -> "no_multi".equalsIgnoreCase(str(f)));
        Map<String, KeySpec> subcommands = new HashMap<>();
        if (info.size() >= 10) {
            for (Object sub : asList(info.get(9))) {
                List<Object> subInfo = asList(sub);
                if (subInfo.size() >= 6) {
                    subcommands.put(str(subInfo.get(0)).toLowerCase(Locale.ROOT), parseSpec(subInfo));
                }
            }
        }
        return new KeySpec(num(info.get(1)), num(info.get(3)), num(info.get(4)), num(info.get(5)), movable, noMulti,
                subcommands);
    }

    private List<byte[]> getKeysFromServer(GlideClusterClient client, byte[][] args) {
        GlideString[] cmd = new GlideString[args.length + 2];
        cmd[0] = GlideString.of("COMMAND");
        cmd[1] = GlideString.of("GETKEYS");
        for (int i = 0; i < args.length; i++) {
            cmd[i + 2] = GlideString.of(args[i]);
        }
        try {
            Object value = client.customCommand(cmd).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSingleValue();
            List<byte[]> keys = new ArrayList<>();
            for (Object key : asList(value)) {
                keys.add(bytes(key));
            }
            return keys;
        } catch (Exception e) {
            // "The command has no key arguments", unknown command, bad arity, ...
            log.debug("COMMAND GETKEYS {} returned no keys: {}", lower(args[0]), e.getMessage());
            return List.of();
        }
    }

    private static String lower(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }

    private static List<Object> asList(Object value) {
        return switch (value) {
            case Object[] arr -> Arrays.asList(arr);
            case Collection<?> c -> new ArrayList<>(c);
            case null, default -> List.of();
        };
    }

    private static String str(Object value) {
        return switch (value) {
            case GlideString gs -> gs.getString();
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            case null -> "";
            default -> value.toString();
        };
    }

    private static byte[] bytes(Object value) {
        return switch (value) {
            case GlideString gs -> gs.getBytes();
            case byte[] b -> b;
            default -> str(value).getBytes(StandardCharsets.UTF_8);
        };
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(str(value));
    }
}
