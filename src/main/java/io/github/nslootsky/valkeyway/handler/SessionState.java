/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler;

import com.github.tonivade.resp.command.Session;
import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility for managing per-session state stored in the resp-server Session object.
 * Tracks current DB, Glide client, transaction state, and scan cursor.
 */
public class SessionState {

    private static final String CURRENT_DB = "currentDb";
    private static final String GLIDE_CLIENT = "glideClient";
    private static final String IN_TRANSACTION = "inTransaction";
    private static final String TRANSACTION_COMMANDS = "transactionCommands";
    private static final String TRANSACTION_ERROR = "transactionError";
    private static final String TRANSACTION_SLOTS = "transactionSlots";
    private static final String SCAN_CURSOR_ID = "scanCursorId";

    private SessionState() {}

    public static int getCurrentDb(Session session) {
        return (Integer) session.getValue(CURRENT_DB).orElse(0);
    }

    public static void setCurrentDb(Session session, int db) {
        session.putValue(CURRENT_DB, db);
    }

    public static GlideClusterClient getOrCreateGlideClient(Session session, GlideClientCache cache) {
        return (GlideClusterClient) session.getValue(GLIDE_CLIENT).orElseGet(() -> {
            int db = getCurrentDb(session);
            GlideClusterClient client = cache.getClient(db);
            session.putValue(GLIDE_CLIENT, client);
            return client;
        });
    }

    public static void setGlideClient(Session session, GlideClusterClient client) {
        session.putValue(GLIDE_CLIENT, client);
    }

    public static void clearGlideClient(Session session) {
        session.putValue(GLIDE_CLIENT, null);
    }

    public static boolean isInTransaction(Session session) {
        return (Boolean) session.getValue(IN_TRANSACTION).orElse(false);
    }

    public static void setInTransaction(Session session, boolean inTx) {
        session.putValue(IN_TRANSACTION, inTx);
    }

    @SuppressWarnings("unchecked")
    public static List<byte[][]> getTransactionCommands(Session session) {
        return (List<byte[][]>) session.getValue(TRANSACTION_COMMANDS).orElse(new ArrayList<>());
    }

    public static void setTransactionCommands(Session session, List<byte[][]> commands) {
        session.putValue(TRANSACTION_COMMANDS, commands);
    }

    public static void clearTransactionCommands(Session session) {
        session.putValue(TRANSACTION_COMMANDS, new ArrayList<byte[][][]>());
    }

    public static String getTransactionError(Session session) {
        return (String) session.getValue(TRANSACTION_ERROR).orElse(null);
    }

    public static void clearTransactionError(Session session) {
        session.putValue(TRANSACTION_ERROR, null);
    }

    @SuppressWarnings("unchecked")
    public static Set<Integer> getTransactionSlots(Session session) {
        return (Set<Integer>) session.getValue(TRANSACTION_SLOTS).orElse(new HashSet<>());
    }

    public static void setTransactionSlots(Session session, Set<Integer> slots) {
        session.putValue(TRANSACTION_SLOTS, slots);
    }

    public static void clearTransactionSlots(Session session) {
        session.putValue(TRANSACTION_SLOTS, new HashSet<Integer>());
    }

    public static void clearTransactionState(Session session) {
        clearTransactionCommands(session);
        clearTransactionSlots(session);
        clearTransactionError(session);
        setInTransaction(session, false);
    }

    public static String getScanCursorId(Session session) {
        return (String) session.getValue(SCAN_CURSOR_ID).orElse(null);
    }

    public static void setScanCursorId(Session session, String cursorId) {
        session.putValue(SCAN_CURSOR_ID, cursorId);
    }

    public static void clearScanCursorId(Session session) {
        session.putValue(SCAN_CURSOR_ID, null);
    }
}
