/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Key positions for common "movable keys" commands, whose keys depend on their arguments
 * (a numkeys count, a STREAMS marker, ...). Parsing them locally keeps COMMAND GETKEYS
 * round trips off the hot path.
 *
 * <p>Relies on upstream behavior: [upstream:V4] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
final class MovableKeys {

    private MovableKeys() {}

    /**
     * @param name lowercase command name
     * @return the keys, or {@code null} if this command isn't handled here or its arguments
     *         are malformed (the caller then falls back)
     */
    static List<byte[]> parse(String name, byte[][] args) {
        try {
            return switch (name) {
                // EVAL script numkeys key... arg...
                case "eval", "evalsha", "eval_ro", "evalsha_ro", "fcall", "fcall_ro" -> numkeysAt(args, 2);
                // ZUNION numkeys key... [options]
                case "zunion", "zinter", "zdiff", "zintercard", "sintercard", "lmpop", "zmpop" -> numkeysAt(args, 1);
                // BLMPOP timeout numkeys key...
                case "blmpop", "bzmpop" -> numkeysAt(args, 2);
                // ZUNIONSTORE destination numkeys key...
                case "zunionstore", "zinterstore", "zdiffstore" -> {
                    List<byte[]> keys = numkeysAt(args, 2);
                    if (keys != null) {
                        keys.addFirst(args[1]);
                    }
                    yield keys;
                }
                // MSETEX numkeys key value [key value ...] [options]
                case "msetex" -> pairsAfterNumkeys(args);
                // XREAD [COUNT n] [BLOCK ms] STREAMS key... id...
                case "xread", "xreadgroup" -> streams(args);
                // SORT key [... STORE destination]
                case "sort", "sort_ro" -> withStoreOption(args, "STORE");
                case "georadius", "georadiusbymember" -> withStoreOption(args, "STORE", "STOREDIST");
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<byte[]> numkeysAt(byte[][] args, int index) {
        if (index >= args.length) {
            return null;
        }
        int numkeys = Integer.parseInt(ascii(args[index]));
        if (numkeys < 0 || index + numkeys >= args.length) {
            return null;
        }
        List<byte[]> keys = new ArrayList<>(numkeys);
        for (int i = index + 1; i <= index + numkeys; i++) {
            keys.add(args[i]);
        }
        return keys;
    }

    private static List<byte[]> pairsAfterNumkeys(byte[][] args) {
        if (args.length < 2) {
            return null;
        }
        int numkeys = Integer.parseInt(ascii(args[1]));
        if (numkeys < 1 || 2 + 2L * numkeys > args.length) { // each key needs its value
            return null;
        }
        List<byte[]> keys = new ArrayList<>(numkeys);
        for (int i = 0; i < numkeys; i++) {
            keys.add(args[2 + 2 * i]);
        }
        return keys;
    }

    private static List<byte[]> streams(byte[][] args) {
        for (int i = 1; i < args.length; i++) {
            if (ascii(args[i]).equalsIgnoreCase("STREAMS")) {
                int remaining = args.length - i - 1;
                if (remaining == 0 || remaining % 2 != 0) {
                    return null;
                }
                List<byte[]> keys = new ArrayList<>(remaining / 2);
                for (int k = i + 1; k <= i + remaining / 2; k++) {
                    keys.add(args[k]);
                }
                return keys;
            }
        }
        return null;
    }

    private static List<byte[]> withStoreOption(byte[][] args, String... storeOptions) {
        if (args.length < 2) {
            return null;
        }
        List<byte[]> keys = new ArrayList<>(2);
        keys.add(args[1]);
        for (int i = 2; i < args.length - 1; i++) {
            String arg = ascii(args[i]);
            for (String option : storeOptions) {
                if (arg.equalsIgnoreCase(option)) {
                    keys.add(args[i + 1]);
                }
            }
        }
        return keys;
    }

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
