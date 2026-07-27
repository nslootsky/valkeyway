package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import io.github.nslootsky.proxy.handler.SessionState;

@Command("discard")
public class DiscardCommand implements RespCommand {

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (!SessionState.isInTransaction(session)) {
            return RedisToken.error("ERR DISCARD without MULTI");
        }
        SessionState.clearTransactionState(session);
        return RedisToken.status("OK");
    }
}
