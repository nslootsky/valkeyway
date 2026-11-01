/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps command names (case-insensitive) to handlers; anything unregistered goes to the fallback.
 */
public final class CommandTable {

    private final Map<String, CommandHandler> handlers = new HashMap<>();
    private final CommandHandler fallback;

    public CommandTable(CommandHandler fallback) {
        this.fallback = fallback;
    }

    public CommandTable register(String name, CommandHandler handler) {
        handlers.put(name.toLowerCase(Locale.ROOT), handler);
        return this;
    }

    public CommandHandler lookup(byte[] name) {
        CommandHandler handler = handlers.get(lowercase(name));
        return handler != null ? handler : fallback;
    }

    private static String lowercase(byte[] name) {
        byte[] lower = new byte[name.length];
        for (int i = 0; i < name.length; i++) {
            byte b = name[i];
            lower[i] = b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
        }
        return new String(lower, StandardCharsets.ISO_8859_1);
    }
}
