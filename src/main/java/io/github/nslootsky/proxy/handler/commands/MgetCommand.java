package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.annotation.ParamLength;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MGET command handler. Retrieves values for multiple keys across slots.
 */
@Command("mget")
@ParamLength(value = 1, option = 1000)
public class MgetCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(MgetCommand.class);

    private final GlideClientCache glideClientCache;

    public MgetCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        List<String> keys = new ArrayList<>();
        for (var param : request.getParams()) {
            keys.add(param.toString());
        }
        String[] keyArray = keys.toArray(new String[0]);
        log.debug("MGET {} keys={}", keyArray.length, keyArray);
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            Object[] results = client.mget(keyArray).get();
            log.debug("MGET OK results={}", results.length);
            return TokenUtils.toRedisArray(results);
        } catch (Exception e) {
            log.error("MGET ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
