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
 * SELECT command handler. Switches the session to the specified database index.
 * Client is created lazily on first actual use. Not allowed inside MULTI/EXEC.
 */
@Command("select")
public class SelectCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(SelectCommand.class);

    public SelectCommand() {
    }

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (SessionState.isInTransaction(session)) {
            log.debug("SELECT ERR not allowed in MULTI/EXEC");
            return RedisToken.error("ERR SELECT not allowed in MULTI/EXEC");
        }
        if (request.getLength() < 1) {
            log.debug("SELECT ERR wrong number of arguments");
            return RedisToken.error("ERR wrong number of arguments for 'select' command");
        }
        try {
            int db = Integer.parseInt(request.getParam(0).toString());
            int currentDb = SessionState.getCurrentDb(session);
            log.debug("SELECT db={} currentDb={}", db, currentDb);
            if (db != currentDb) {
                SessionState.setCurrentDb(session, db);
            }
            return RedisToken.status("OK");
        } catch (NumberFormatException e) {
            log.error("SELECT ERR {}", e.getMessage());
            return RedisToken.error("ERR value is not an integer or out of range");
        }
    }
}
