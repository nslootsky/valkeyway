/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * State shared by all connections: the command table, the executor commands run on, and the
 * registry of connected clients (for client IDs, the client limit and, later, CLIENT LIST).
 */
final class ServerContext implements ClientRegistry {

    private final CommandTable commands;
    private final Executor executor;
    private final int maxClients;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Connection> clients = new ConcurrentHashMap<>();

    ServerContext(CommandTable commands, Executor executor, int maxClients) {
        this.commands = commands;
        this.executor = executor;
        this.maxClients = maxClients;
    }

    /** Registers a new client, or returns null if the client limit is reached. */
    Connection register(Channel channel) {
        if (clients.size() >= maxClients) {
            return null;
        }
        Connection connection = new Connection(nextId.getAndIncrement(), channel, commands, executor, this);
        clients.put(connection.id(), connection);
        return connection;
    }

    void unregister(Connection connection) {
        clients.remove(connection.id());
    }

    @Override
    public Collection<Connection> clients() {
        return Collections.unmodifiableCollection(clients.values());
    }

    @Override
    public Connection find(long id) {
        return clients.get(id);
    }

    @Override
    public int maxClients() {
        return maxClients;
    }
}
