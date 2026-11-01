/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SUBSCRIBE, PSUBSCRIBE and SSUBSCRIBE, and their UNSUBSCRIBE counterparts, with Valkey's
 * confirmations: one push per name, with the client's subscription count after it. [upstream:V8]
 *
 * <p>Subscriptions go through {@link PubSubHub}, never over the backend connections clients share.
 * Channel names are passed to Glide as text, so names that aren't valid UTF-8 aren't kept exactly.
 * [upstream:G14]
 */
public class PubSubCommand implements CommandHandler {

    private final PubSubHub hub;
    private final Kind kind;
    private final boolean subscribe;

    private PubSubCommand(PubSubHub hub, Kind kind, boolean subscribe) {
        this.hub = hub;
        this.kind = kind;
        this.subscribe = subscribe;
    }

    public static PubSubCommand subscribe(PubSubHub hub, Kind kind) {
        return new PubSubCommand(hub, kind, true);
    }

    public static PubSubCommand unsubscribe(PubSubHub hub, Kind kind) {
        return new PubSubCommand(hub, kind, false);
    }

    /** Subscription state can't be part of an EXEC, which runs on a node. */
    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    @Override
    public boolean allowedWhileSubscribed() {
        return true;
    }

    @Override
    public Reply execute(Request request) {
        return subscribe ? subscribe(request) : unsubscribe(request);
    }

    private Reply subscribe(Request request) {
        if (request.argCount() == 0) {
            return Reply.error("ERR wrong number of arguments for '" + kind.subscribeCommand + "' command");
        }
        Connection connection = request.connection();
        PubSubSession session = PubSubSession.of(connection, hub);
        Set<String> fresh = new LinkedHashSet<>();
        for (int i = 0; i < request.argCount(); i++) {
            String name = request.argString(i);
            if (!session.has(kind, name)) {
                fresh.add(name);
            }
        }
        try {
            hub.subscribe(session, kind, fresh);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            if (session.empty()) {
                PubSubSession.end(connection);
            }
            return ServerErrors.error(e);
        }
        List<Reply> confirmations = new ArrayList<>(request.argCount());
        for (int i = 0; i < request.argCount(); i++) {
            session.add(kind, request.argString(i));
            confirmations.add(new Reply.Push(List.of(kind.subscribe, new Reply.Bulk(request.arg(i)),
                    Reply.integer(session.count(kind)))));
        }
        connection.subscribed(true);
        return new Reply.Multi(confirmations);
    }

    private Reply unsubscribe(Request request) {
        Connection connection = request.connection();
        PubSubSession session = connection.get(PubSubSession.KEY);
        List<byte[]> names = new ArrayList<>();
        if (request.argCount() > 0) {
            for (int i = 0; i < request.argCount(); i++) {
                names.add(request.arg(i));
            }
        } else if (session != null) {
            for (String name : session.all(kind)) {
                names.add(name.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (names.isEmpty()) {
            int count = session == null ? 0 : session.count(kind);
            return new Reply.Multi(List.of(new Reply.Push(List.of(kind.unsubscribe, Reply.Null.BULK, Reply.integer(count)))));
        }
        List<Reply> confirmations = new ArrayList<>(names.size());
        List<String> removed = new ArrayList<>();
        for (byte[] name : names) {
            String text = new String(name, StandardCharsets.UTF_8);
            if (session != null && session.remove(kind, text)) {
                removed.add(text);
            }
            confirmations.add(new Reply.Push(List.of(kind.unsubscribe, new Reply.Bulk(name),
                    Reply.integer(session == null ? 0 : session.count(kind)))));
        }
        if (session != null) {
            hub.unsubscribe(session, kind, removed);
            if (session.empty()) {
                connection.subscribed(false);
            }
        }
        return new Reply.Multi(confirmations);
    }
}
