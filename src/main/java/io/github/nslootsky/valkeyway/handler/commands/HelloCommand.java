/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.util.List;

/**
 * HELLO: answers like a standalone server speaking RESP2, without contacting the cluster.
 */
public class HelloCommand implements CommandHandler {

    @Override
    public Reply execute(Request request) {
        return new Reply.MapReply(List.of(
                Reply.bulk("server"), Reply.bulk("valkeyway"),
                Reply.bulk("version"), Reply.bulk("0.1.0"),
                Reply.bulk("proto"), Reply.integer(2),
                Reply.bulk("id"), Reply.integer(request.connection().id()),
                Reply.bulk("mode"), Reply.bulk("standalone"),
                Reply.bulk("role"), Reply.bulk("master"),
                Reply.bulk("modules"), Reply.EMPTY_ARRAY));
    }
}
