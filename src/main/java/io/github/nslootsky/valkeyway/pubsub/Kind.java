/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

import io.github.nslootsky.valkeyway.protocol.Reply;

/** The three kinds of subscription, with the names Valkey uses for their confirmations and messages. */
public enum Kind {
    CHANNEL("subscribe", "unsubscribe", "message"),
    PATTERN("psubscribe", "punsubscribe", "pmessage"),
    SHARD("ssubscribe", "sunsubscribe", "smessage");

    final String subscribeCommand;
    final Reply subscribe;
    final Reply unsubscribe;
    final Reply message;

    Kind(String subscribe, String unsubscribe, String message) {
        this.subscribeCommand = subscribe;
        this.subscribe = Reply.bulk(subscribe);
        this.unsubscribe = Reply.bulk(unsubscribe);
        this.message = Reply.bulk(message);
    }
}
