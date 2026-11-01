/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.InlineCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

/**
 * A connection-scoped command the proxy answers with a fixed reply instead of forwarding it, since
 * forwarding would change a backend connection that every client shares. [upstream:G11]
 */
public final class LocalCommand implements InlineCommandHandler {

    private final Reply reply;
    private final InTransaction inTransaction;

    private LocalCommand(Reply reply, InTransaction inTransaction) {
        this.reply = reply;
        this.inTransaction = inTransaction;
    }

    /**
     * Accepted and ignored (READONLY, READWRITE: replica reads are a cluster matter). Inside MULTI it's
     * rejected, since queueing it would send it over the shared connection inside EXEC.
     */
    public static LocalCommand accept(Reply reply) {
        return new LocalCommand(reply, InTransaction.REJECT);
    }

    /** Rejected: the proxy can't offer it (MONITOR, Pub/Sub subscriptions). */
    public static LocalCommand unsupported(String name, String why) {
        return new LocalCommand(Reply.error("ERR " + name + " is not supported by this proxy" + why), InTransaction.REJECT);
    }

    @Override
    public InTransaction inTransaction() {
        return inTransaction;
    }

    @Override
    public Reply execute(Request request) {
        return reply;
    }
}
