/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import io.github.nslootsky.valkeyway.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MULTI command handler. Begins a transaction; subsequent commands are queued.
 */
@Command("multi")
public class MultiCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(MultiCommand.class);

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (SessionState.isInTransaction(session)) {
            log.debug("MULTI ERR nested MULTI not allowed");
            return RedisToken.error("ERR MULTI calls can not be nested");
        }
        log.debug("MULTI starting transaction");
        SessionState.setInTransaction(session, true);
        SessionState.clearTransactionCommands(session);
        SessionState.clearTransactionSlots(session);
        SessionState.clearTransactionError(session);
        return RedisToken.status("OK");
    }
}
