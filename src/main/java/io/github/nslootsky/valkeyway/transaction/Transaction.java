/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.transaction;

import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.ConnectionKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A connection's open transaction: the commands queued since MULTI, the slots of their keys, and
 * whether a queue-time error has doomed it. The connection's own {@code inTransaction} flag says
 * whether one is open; this holds its contents.
 */
public final class Transaction {

    private static final ConnectionKey<Transaction> KEY = ConnectionKey.of("transaction");

    private final List<byte[][]> commands = new ArrayList<>();
    private final Set<Integer> slots = new HashSet<>();
    private String error;

    /** MULTI: opens a transaction on {@code connection}. */
    public static void begin(Connection connection) {
        connection.set(KEY, new Transaction());
        connection.inTransaction(true);
    }

    /** The open transaction of {@code connection}. */
    public static Transaction current(Connection connection) {
        Transaction transaction = connection.get(KEY);
        if (transaction == null) {
            transaction = new Transaction();
            connection.set(KEY, transaction);
        }
        return transaction;
    }

    /** EXEC or DISCARD: closes the transaction and returns what it held. */
    public static Transaction end(Connection connection) {
        Transaction transaction = connection.remove(KEY);
        connection.inTransaction(false);
        return transaction != null ? transaction : new Transaction();
    }

    public List<byte[][]> commands() {
        return commands;
    }

    public Set<Integer> slots() {
        return slots;
    }

    /** The reply EXEC must give instead of running (EXECABORT), or {@code null}. */
    public String error() {
        return error;
    }

    void error(String error) {
        this.error = error;
    }
}
