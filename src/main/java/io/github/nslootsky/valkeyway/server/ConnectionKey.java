/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

/**
 * Names a piece of per-connection state that a command handler keeps on the {@link Connection}
 * (a transaction's queue, a SCAN cursor, ...). Keys compare by identity: declare each once, as a
 * constant of the class that owns the state.
 *
 * @param <T> the type of the state
 */
public final class ConnectionKey<T> {

    private final String name;

    private ConnectionKey(String name) {
        this.name = name;
    }

    public static <T> ConnectionKey<T> of(String name) {
        return new ConnectionKey<>(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
