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
 */
public class CommandKeyExtractor {

    private static final Logger log = LoggerFactory.getLogger(CommandKeyExtractor.class);
    private static final long TIMEOUT_SECONDS = 5;

    record KeySpec(int first, int last, int step, boolean movable, Map<String, KeySpec> subcommands) {}

    private volatile Map<String, KeySpec> table;

    /**
     * Returns the key arguments of {@code args} (args[0] is the command name).
     */
    public List<byte[]> extractKeys(GlideClusterClient client, byte[][] args) {
        if (args.length < 2) {
            return List.of();
        }
        Map<String, KeySpec> specs = loadTable(client);
        KeySpec spec = specs == null ? null : specs.get(lower(args[0]));
        if (spec != null && !spec.subcommands().isEmpty()) {
            KeySpec sub = spec.subcommands().get(lower(args[0]) + "|" + lower(args[1]));
            if (sub != null) {
                spec = sub;
            }
        }
        if (spec == null || spec.movable()) {
            return getKeysFromServer(client, args);
        }
        return keysFromSpec(spec, args);
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
        Map<String, KeySpec> subcommands = new HashMap<>();
        if (info.size() >= 10) {
            for (Object sub : asList(info.get(9))) {
                List<Object> subInfo = asList(sub);
                if (subInfo.size() >= 6) {
                    subcommands.put(str(subInfo.get(0)).toLowerCase(Locale.ROOT), parseSpec(subInfo));
                }
            }
        }
        return new KeySpec(num(info.get(3)), num(info.get(4)), num(info.get(5)), movable, subcommands);
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
