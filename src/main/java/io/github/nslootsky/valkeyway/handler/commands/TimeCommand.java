/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.InlineCommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.time.Instant;

/**
 * TIME: the proxy's clock, as [unix seconds, microseconds].
 */
public class TimeCommand implements InlineCommandHandler {

    @Override
    public Reply execute(Request request) {
        if (request.argCount() != 0) {
            return Reply.error("ERR wrong number of arguments for 'time' command");
        }
        Instant now = Instant.now();
        return Reply.array(Reply.bulk(Long.toString(now.getEpochSecond())),
                Reply.bulk(Long.toString(now.getNano() / 1000)));
    }
}
