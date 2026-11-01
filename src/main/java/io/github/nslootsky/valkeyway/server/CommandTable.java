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

    /**
     * The handler for {@code name} on a connection that may be inside MULTI: commands that would be
     * queued go to the fallback handler (which queues them), and rejected ones to the rejecting handler.
     */
    public CommandHandler lookup(byte[] name, boolean inTransaction) {
        CommandHandler handler = lookup(name);
        if (!inTransaction) {
            return handler;
        }
        return switch (handler.inTransaction()) {
            case RUN -> handler;
            case QUEUE -> fallback;
            case REJECT -> rejectInTransaction != null ? rejectInTransaction : fallback;
        };
    }

    private CommandHandler rejectInTransaction;

    /** Answers commands whose handler declares {@link CommandHandler.InTransaction#REJECT} inside MULTI. */
    public CommandTable rejectInTransaction(CommandHandler handler) {
        this.rejectInTransaction = handler;
        return this;
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
