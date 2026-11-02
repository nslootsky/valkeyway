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

    private final Boolean readOnly;

    private LocalCommand(Reply reply, InTransaction inTransaction, Boolean readOnly) {
        this.reply = reply;
        this.inTransaction = inTransaction;
        this.readOnly = readOnly;
    }

    /**
     * READONLY (true) or READWRITE (false): whether this connection's reads may go to replicas,
     * which they do when {@code proxy.replica-reads.connections} is readonly. Rejected inside MULTI.
     */
    public static LocalCommand readOnly(boolean readOnly) {
        return new LocalCommand(Reply.OK, InTransaction.REJECT, readOnly);
    }

    /** Rejected: the proxy can't offer it (MONITOR, Pub/Sub subscriptions). */
    public static LocalCommand unsupported(String name, String why) {
        return new LocalCommand(Reply.error("ERR " + name + " is not supported by this proxy" + why), InTransaction.REJECT, null);
    }

    @Override
    public InTransaction inTransaction() {
        return inTransaction;
    }

    @Override
    public Reply execute(Request request) {
        if (readOnly != null) {
            request.connection().readOnly(readOnly);
        }
        return reply;
    }
}
