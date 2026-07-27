package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import io.github.nslootsky.proxy.handler.SessionState;

@Command("multi")
public class MultiCommand implements RespCommand {

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (SessionState.isInTransaction(session)) {
            return RedisToken.error("ERR MULTI calls can not be nested");
        }
        SessionState.setInTransaction(session, true);
        SessionState.clearTransactionCommands(session);
        SessionState.clearTransactionSlots(session);
        SessionState.clearTransactionError(session);
        return RedisToken.status("OK");
    }
}
