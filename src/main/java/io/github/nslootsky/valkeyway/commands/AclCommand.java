/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.AsyncCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.transaction.TransactionQueue;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * ACL: WHOAMI names the client's proxy user; every other subcommand goes to the cluster like any
 * command (the ones that change a node's users are refused by {@code proxy.node-admin-commands}).
 * Forwarded, WHOAMI would name the proxy's own backend user instead.
 */
public class AclCommand implements AsyncCommandHandler {

    private final CatchAllCommand forward;

    public AclCommand(CatchAllCommand forward) {
        this.forward = forward;
    }

    /** Everything but WHOAMI goes to the cluster. */
    private static boolean forwarded(Request request) {
        return request.argCount() != 1 || !request.argString(0).equalsIgnoreCase("whoami");
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    @Override
    public boolean canRunAsync(Request request) {
        return forwarded(request) && forward.canRunAsync(request);
    }

    @Override
    public CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor) {
        return forward.executeAsync(request, replyExecutor);
    }

    @Override
    public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
        return forward.executeBatchAsync(requests, replyExecutor);
    }

    @Override
    public Reply execute(Request request) throws InterruptedException {
        if (forwarded(request)) {
            return forward.execute(request);
        }
        if (request.connection().inTransaction()) {
            return TransactionQueue.reject(request.connection(),
                    "ERR Command 'acl|whoami' is not supported inside a transaction by this proxy");
        }
        return Reply.bulk(request.connection().user());
    }
}
