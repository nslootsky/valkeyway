/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;

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
     * A connection that hasn't authenticated gets NOAUTH for everything not allowed before AUTH, and a
     * RESP2 connection with subscriptions the subscribed-mode error for everything not allowed then.
     */
    public CommandHandler lookup(byte[] name, boolean inTransaction, boolean authenticated, boolean subscribedResp2) {
        CommandHandler handler = lookup(name);
        if (!authenticated && !handler.allowedBeforeAuth()) {
            return NOAUTH;
        }
        if (subscribedResp2 && !handler.allowedWhileSubscribed()) {
            return SUBSCRIBED;
        }
        if (!inTransaction) {
            return handler;
        }
        return switch (handler.inTransaction()) {
            case RUN -> handler;
            case QUEUE -> fallback;
            case REJECT -> rejectInTransaction != null ? rejectInTransaction : fallback;
        };
    }

    // [upstream:V7]
    private static final InlineCommandHandler NOAUTH = _ -> Reply.error("NOAUTH Authentication required.");

    // [upstream:V8]
    private static final InlineCommandHandler SUBSCRIBED = r -> Reply.error("ERR Can't execute '"
            + lowercase(r.args()[0]) + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context");

    private CommandHandler rejectInTransaction;
    private CommandListener listener = CommandListener.NONE;

    /** Told about every answered command; see {@link CommandListener}. */
    public CommandTable listener(CommandListener listener) {
        this.listener = listener;
        return this;
    }

    CommandListener listener() {
        return listener;
    }

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
