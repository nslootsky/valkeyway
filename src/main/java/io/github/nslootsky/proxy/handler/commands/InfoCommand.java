package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InfoCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(InfoCommand.class);

    private final GlideClientCache glideClientCache;
    private final MetricsCollector metrics;

    public InfoCommand(GlideClientCache glideClientCache, MetricsCollector metrics) {
        this.glideClientCache = glideClientCache;
        this.metrics = metrics;
    }

    @Override
    public RedisToken execute(Request request) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Server\r\n");
            sb.append("redis_version:7.2.0\r\n");
            sb.append("redis_mode:standalone\r\n");
            sb.append("os:Linux x86_64\r\n");
            sb.append("tcp_port:6379\r\n");
            sb.append("# Proxy\r\n");
            sb.append("active_clients:").append(glideClientCache.getActiveClients()).append("\r\n");
            sb.append("# Stats\r\n");
            sb.append("total_commands_processed:").append(metrics.getTotalCommands()).append("\r\n");
            sb.append("total_errors:").append(metrics.getTotalErrors()).append("\r\n");
            return RedisToken.string(sb.toString());
        } catch (Exception e) {
            log.error("INFO command failed", e);
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
