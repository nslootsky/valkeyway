/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * HELLO command handler. Returns standalone mode response for Glide compatibility.
 * Does not require backend connection - returns hardcoded server info.
 */
public class HelloCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(HelloCommand.class);

    public HelloCommand() {
    }

    @Override
    public RedisToken execute(Request request) {
        log.debug("HELLO args={}", request.getParams());
        Map<String, Object> response = Map.of(
                "server", "valkeyway",
                "version", "7.0.0",
                "proto", 3,
                "id", System.currentTimeMillis(),
                "mode", "standalone",
                "modules", java.util.List.of()
        );
        log.debug("HELLO OK mode=standalone");
        return TokenUtils.toRedisToken(response);
    }
}
