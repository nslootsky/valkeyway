/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.transaction;

import io.github.nslootsky.valkeyway.server.ConnectionKey;
import io.github.nslootsky.valkeyway.server.Connection;

/** Starts, looks up and ends each connection's {@link WatchSession}. */
public final class WatchSessions {

    private final WatchClientPool pool;

    public WatchSessions(WatchClientPool pool) {
        this.pool = pool;
    }

    private static final ConnectionKey<WatchSession> KEY = ConnectionKey.of("watch");

    /** The connection's session, or {@code null} if it has nothing watched. */
    public WatchSession current(Connection connection) {
        return connection.get(KEY);
    }

    /**
     * The session to add watches to, leasing a client if the connection has none yet.
     *
     * @return the session, or {@code null} if no client became free in time
     */
    public WatchSession forWatch(Connection connection) throws InterruptedException {
        WatchSession session = current(connection);
        if (session != null) {
            if (session.db() != connection.db()) {
                session.markBroken();
            }
            return session;
        }
        var lease = pool.acquire(connection.db());
        if (lease == null) {
            return null;
        }
        session = new WatchSession(lease, connection.db());
        connection.set(KEY, session);
        if (connection.isClosed()) {
            // Disconnected meanwhile; its close may have missed the new session.
            end(connection);
            return null;
        }
        return session;
    }

    public void watched(WatchSession session, byte[][] keys) {
        session.watched(keys);
    }

    /** Detaches the session from the connection and returns it (EXEC takes it over). */
    public WatchSession take(Connection connection) {
        return connection.remove(KEY);
    }

    /** Drops the connection's watches (UNWATCH, DISCARD). */
    public void end(Connection connection) {
        WatchSession session = take(connection);
        if (session != null) {
            session.close();
        }
    }

    /** After SELECT: watches in the old database can't be checked by an EXEC in the new one. */
    public void selected(Connection connection) {
        WatchSession session = current(connection);
        if (session != null && session.db() != connection.db()) {
            session.markBroken();
        }
    }
}
