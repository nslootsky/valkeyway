/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.transaction;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.backend.routing.ClusterSlots;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.Connection;

/**
 * Queues commands between MULTI and EXEC. The proxy keeps the transaction itself and sends it to the
 * cluster at EXEC, so it also does the checks a server does while queueing. [upstream:V6]
 */
public final class TransactionQueue {

    /** EXEC's reply after a command was rejected while queueing. */
    public static final String EXECABORT = "EXECABORT Transaction discarded because of previous errors.";

    private final BackendClients clients;
    private final CommandKeyExtractor keys;

    public TransactionQueue(BackendClients clients, CommandKeyExtractor keys) {
        this.clients = clients;
        this.keys = keys;
    }

    /**
     * Queues {@code args}. Like Valkey, a command that can't run (unknown, wrong number of arguments,
     * not allowed in a transaction) is answered with its error now, and makes EXEC fail with EXECABORT.
     */
    public Reply queue(Connection connection, byte[][] args) {
        GlideClusterClient client = clients.getClient(connection.db());
        String error = keys.queueError(client, args);
        if (error != null) {
            return reject(connection, error);
        }
        Transaction transaction = Transaction.current(connection);
        if (transaction.error() == null) {
            for (byte[] key : keys.extractKeys(client, args)) {
                transaction.slots().add(ClusterSlots.slot(key));
            }
            transaction.commands().add(args);
        }
        return Reply.QUEUED;
    }

    /** Answers a command with {@code error} and discards the transaction (EXEC answers EXECABORT). */
    public static Reply reject(Connection connection, String error) {
        Transaction.current(connection).error(EXECABORT);
        return Reply.error(error);
    }
}
