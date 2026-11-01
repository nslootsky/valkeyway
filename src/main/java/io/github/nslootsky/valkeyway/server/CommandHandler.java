/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;

/**
 * Executes one command. Runs on the connection's virtual thread, so it may block (e.g. on a Glide
 * future) without holding up the network threads or other connections.
 */
@FunctionalInterface
public interface CommandHandler {

    Reply execute(Request request) throws Exception;

    /** What happens to this handler's commands between MULTI and EXEC. */
    enum InTransaction {
        /** The handler runs and deals with the transaction itself (MULTI, EXEC, WATCH, ...). */
        RUN,
        /** Queued for the backend, like any key command (PING, ECHO, TIME, ...). */
        QUEUE,
        /** Answered from proxy state that can't be reproduced inside a transaction; rejected. */
        REJECT
    }

    default InTransaction inTransaction() {
        return InTransaction.QUEUE;
    }
}
