package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.SessionState;

@Command("info")
public class InfoCommand implements RespCommand {

    private final GlideClientCache glideClientCache;

    public InfoCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            String info = client.info().get().getSingleValue();
            return RedisToken.string(info);
        } catch (Exception e) {
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
