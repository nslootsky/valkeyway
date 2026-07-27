package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.SessionState;

@Command("select")
public class SelectCommand implements RespCommand {

    private final GlideClientCache glideClientCache;

    public SelectCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (SessionState.isInTransaction(session)) {
            return RedisToken.error("ERR SELECT not allowed in MULTI/EXEC");
        }
        if (request.getLength() < 1) {
            return RedisToken.error("ERR wrong number of arguments for 'select' command");
        }
        try {
            int db = Integer.parseInt(request.getParam(0).toString());
            int currentDb = SessionState.getCurrentDb(session);
            if (db != currentDb) {
                SessionState.setCurrentDb(session, db);
                GlideClusterClient client = glideClientCache.getClient(db);
                SessionState.setGlideClient(session, client);
            }
            return RedisToken.status("OK");
        } catch (NumberFormatException e) {
            return RedisToken.error("ERR value is not an integer or out of range");
        }
    }
}
