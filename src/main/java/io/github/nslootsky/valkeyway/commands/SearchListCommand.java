/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.AsyncCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * FT._LIST (the search module's list of indexes), sent to the cluster like any command, with each
 * name listed once. Glide sends it to every primary and joins the replies, and with search in
 * coordinator mode every node knows every index, so each name came back once per primary. Without
 * coordinator mode an index lives on one node, and the joined list is already right.
 */
// [upstream:G16] [upstream:V12]
public class SearchListCommand implements AsyncCommandHandler {

    private final CatchAllCommand forward;

    public SearchListCommand(CatchAllCommand forward) {
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
        return forward.executeAsync(request, replyExecutor).thenApply(SearchListCommand::distinct);
    }

    @Override
    public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
        return forward.executeBatchAsync(requests, replyExecutor)
                .thenApply(replies -> replies.stream().map(SearchListCommand::distinct).toList());
    }

    @Override
    public Reply execute(Request request) throws Exception {
        return distinct(forward.execute(request));
    }

    /** The reply with repeated names removed, in first-seen order; other replies (errors) unchanged. */
    static Reply distinct(Reply reply) {
        return switch (reply) {
            case Reply.Array(List<Reply> items) -> new Reply.Array(distinct(items));
            case Reply.SetReply(List<Reply> items) -> new Reply.SetReply(distinct(items));
            default -> reply;
        };
    }

    private static List<Reply> distinct(List<Reply> items) {
        Set<ByteBuffer> seen = new HashSet<>();
        List<Reply> out = new ArrayList<>(items.size());
        for (Reply item : items) {
            // Names are bulk strings; anything else is kept as it is.
            if (!(item instanceof Reply.Bulk(byte[] name)) || seen.add(ByteBuffer.wrap(name))) {
                out.add(item);
            }
        }
        return out;
    }
}
