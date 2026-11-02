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
     * Answered with a fixed reply. READONLY and READWRITE are accepted and ignored: which nodes serve
     * reads is {@code proxy.read-from}'s to decide. ROLE answers as a primary without replicas, like
     * INFO replication: forwarded, it would describe whichever node got it, maybe a replica. Inside
     * MULTI they're rejected, since queued they would go to a backend node inside EXEC.
     */
    public static LocalCommand answer(Reply reply) {
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
