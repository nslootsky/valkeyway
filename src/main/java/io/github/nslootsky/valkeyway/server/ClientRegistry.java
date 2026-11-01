/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import java.util.Collection;

/** The proxy's connected clients, for CLIENT LIST, CLIENT KILL and the like. */
public interface ClientRegistry {

    /** Every connected client, in no particular order. */
    Collection<Connection> clients();

    /** The client with {@code id}, or {@code null}. */
    Connection find(long id);

    /** Most clients accepted at once. */
    int maxClients();
}
