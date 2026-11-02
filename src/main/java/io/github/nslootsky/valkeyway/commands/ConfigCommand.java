/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.AsyncCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * CONFIG, sent to the cluster like any command (CONFIG SET and the like are refused by
 * {@code proxy.node-admin-commands}), but with the values of password settings redacted from CONFIG
 * GET: Valkey returns them in plain text, and through the proxy they're the cluster's credentials.
 */
public class ConfigCommand implements AsyncCommandHandler {

    /** Settings whose values are secrets. */
    static final Set<String> SECRETS = Set.of("requirepass", "masterauth", "primaryauth", "tls-key-file-pass",
            "tls-client-key-file-pass");
    static final Reply REDACTED = Reply.bulk("(redacted by the proxy)");

    private final CatchAllCommand forward;

    public ConfigCommand(CatchAllCommand forward) {
        this.forward = forward;
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN; // queued by the forward handler, as before
    }

    @Override
    public boolean canRunAsync(Request request) {
        return forward.canRunAsync(request);
    }

    @Override
    public CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor) {
        return forward.executeAsync(request, replyExecutor).thenApply(reply -> redact(request, reply));
    }

    @Override
    public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
        return forward.executeBatchAsync(requests, replyExecutor).thenApply(replies -> {
            List<Reply> out = new ArrayList<>(replies.size());
            for (int i = 0; i < replies.size(); i++) {
                out.add(redact(requests.get(i), replies.get(i)));
            }
            return out;
        });
    }

    @Override
    public Reply execute(Request request) throws Exception {
        return redact(request, forward.execute(request));
    }

    /** A CONFIG GET reply with the values of secret settings replaced. */
    static Reply redact(Request request, Reply reply) {
        if (request.argCount() < 2 || !request.argString(0).equalsIgnoreCase("get")) {
            return reply;
        }
        return switch (reply) {
            case Reply.MapReply(List<Reply> kv) -> new Reply.MapReply(redactPairs(kv));
            case Reply.Array(List<Reply> kv) -> new Reply.Array(redactPairs(kv));
            default -> reply;
        };
    }

    private static List<Reply> redactPairs(List<Reply> kv) {
        List<Reply> out = new ArrayList<>(kv);
        for (int i = 0; i + 1 < out.size(); i += 2) {
            if (out.get(i) instanceof Reply.Bulk(byte[] name)
                    && SECRETS.contains(new String(name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT))) {
                out.set(i + 1, REDACTED);
            }
        }
        return out;
    }
}
