package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import io.github.nslootsky.proxy.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DISCARD command handler. Aborts the current transaction.
 */
@Command("discard")
public class DiscardCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(DiscardCommand.class);

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (!SessionState.isInTransaction(session)) {
            log.debug("DISCARD ERR not in MULTI");
            return RedisToken.error("ERR DISCARD without MULTI");
        }
        log.debug("DISCARD clearing transaction");
        SessionState.clearTransactionState(session);
        return RedisToken.status("OK");
    }
}
