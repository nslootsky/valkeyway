package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PING command handler. Supports optional message argument.
 */
@Command("ping")
public class PingCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(PingCommand.class);

    private final GlideClientCache glideClientCache;

    public PingCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            String msg = request.getLength() > 0 ? request.getParam(0).toString() : null;
            log.debug("PING msg={}", msg);
            String result = msg != null ? client.ping(msg).get() : client.ping().get();
            log.debug("PING OK result={}", result);
            return RedisToken.string(result);
        } catch (Exception e) {
            log.error("PING ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
