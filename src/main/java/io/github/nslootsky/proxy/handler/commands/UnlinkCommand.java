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

@Command("unlink")
@ParamLength(value = 1, option = 1000)
public class UnlinkCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(UnlinkCommand.class);

    private final GlideClientCache glideClientCache;

    public UnlinkCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        List<String> keys = new ArrayList<>();
        for (var param : request.getParams()) {
            keys.add(param.toString());
        }
        String[] keyArray = keys.toArray(new String[0]);
        log.debug("UNLINK {} keys", keyArray.length);
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            long deleted = client.unlink(keyArray).get();
            log.debug("UNLINK OK deleted={}", deleted);
            return RedisToken.integer((int) deleted);
        } catch (Exception e) {
            log.error("UNLINK ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
