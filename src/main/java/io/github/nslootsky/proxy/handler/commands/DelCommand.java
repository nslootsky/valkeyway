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

@Command("del")
@ParamLength(value = 1, option = 1000)
public class DelCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(DelCommand.class);

    private final GlideClientCache glideClientCache;

    public DelCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        List<String> keys = new ArrayList<>();
        for (var param : request.getParams()) {
            keys.add(param.toString());
        }
        String[] keyArray = keys.toArray(new String[0]);
        log.debug("DEL {} keys={}", keyArray.length, keyArray.length);
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            long deleted = client.del(keyArray).get();
            log.debug("DEL OK deleted={}", deleted);
            return RedisToken.integer((int) deleted);
        } catch (Exception e) {
            log.error("DEL ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
