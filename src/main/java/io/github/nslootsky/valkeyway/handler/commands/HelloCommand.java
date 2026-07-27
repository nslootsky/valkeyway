package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HELLO command handler. Overrides mode to "standalone" for Glide compatibility.
 */
public class HelloCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(HelloCommand.class);

    private final GlideClientCache glideClientCache;
    private final MetricsCollector metrics;

    public HelloCommand(GlideClientCache glideClientCache, MetricsCollector metrics) {
        this.glideClientCache = glideClientCache;
        this.metrics = metrics;
    }

    @Override
    public RedisToken execute(Request request) {
        List<String> argsList = new ArrayList<>();
        argsList.add("HELLO");
        for (var param : request.getParams()) {
            argsList.add(param.toString());
        }
        String[] cmdArgs = argsList.toArray(new String[0]);
        log.debug("HELLO args={}", argsList);
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            ClusterValue<Object> result = client.customCommand(cmdArgs).get();
            if (result.hasMultiData()) {
                Map<String, Object> multiValue = new HashMap<>(result.getMultiValue());
                multiValue.put("mode", "standalone");
                log.debug("HELLO OK mode=standalone");
                return TokenUtils.toRedisToken(multiValue);
            }
            return TokenUtils.toRedisToken(result);
        } catch (Exception e) {
            log.error("HELLO ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
