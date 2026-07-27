package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.SessionState;

@Command("ping")
public class PingCommand implements RespCommand {

    private final GlideClientCache glideClientCache;

    public PingCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            if (request.getLength() > 1) {
                return RedisToken.string(client.ping(request.getParam(0).toString()).get());
            } else {
                return RedisToken.string(client.ping().get());
            }
        } catch (Exception e) {
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
