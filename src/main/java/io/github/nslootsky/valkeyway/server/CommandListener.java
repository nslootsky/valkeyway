/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;

/**
 * Told about every command a connection answers, whatever handled it (metrics, for example).
 * Called on the connection's event loop, so it must be quick and must not block.
 */
@FunctionalInterface
public interface CommandListener {

    CommandListener NONE = (name, reply, elapsedNanos) -> { };

    /**
     * @param name         the command name as the client sent it
     * @param reply        the reply sent back (an {@link Reply.Error} for failures)
     * @param elapsedNanos from when the command started running until its reply was ready
     */
    void answered(byte[] name, Reply reply, long elapsedNanos);
}
