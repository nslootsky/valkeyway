package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Command("time")
public class TimeCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(TimeCommand.class);

    private final GlideClientCache glideClientCache;

    public TimeCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        try {
            log.debug("TIME");
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            Object[] result = client.time().get();
            log.debug("TIME OK result={}", result);
            return TokenUtils.toRedisArray(result);
        } catch (Exception e) {
            log.error("TIME ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
