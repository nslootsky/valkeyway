/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.annotation.ParamLength;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROXY admin command handler. Supports subcommands: CLUSTER INFO, CONFIG GET/SET,
 * STATS, FLUSHCLIENTS, CLIENTINFO.
 */
@Command("proxy")
@ParamLength(value = 1, option = 10)
public class ValkeywayAdminCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(ValkeywayAdminCommand.class);

    private final GlideClientCache glideClientCache;

    private static final Map<String, String> proxyConfig = new ConcurrentHashMap<>();
    private static final Map<String, String> defaultConfig = Map.of(
            "cluster_slots", "16384",
            "proxy_port", "6379"
    );

    public ValkeywayAdminCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        if (request.getLength() < 1) {
            log.debug("PROXY ERR wrong number of arguments");
            return RedisToken.error("ERR wrong number of arguments for 'proxy' command");
        }

        String subCmd = request.getParam(0).toString().toUpperCase();
        log.debug("PROXY subCmd={}", subCmd);

        if ("CLUSTER".equals(subCmd) && request.getLength() >= 2 && "INFO".equalsIgnoreCase(request.getParam(1).toString())) {
            return handleProxyClusterInfo(request);
        } else if ("CONFIG".equals(subCmd) && request.getLength() >= 2) {
            return handleProxyConfig(request);
        } else if ("STATS".equals(subCmd)) {
            return handleProxyStats();
        } else if ("FLUSHCLIENTS".equals(subCmd)) {
            return handleProxyFlushClients();
        } else if ("CLIENTINFO".equals(subCmd) && request.getLength() >= 2) {
            return handleProxyClientInfo(request);
        } else {
            log.debug("PROXY ERR unknown subcommand={}", subCmd);
            return RedisToken.error("ERR unknown proxy subcommand");
        }
    }

    private RedisToken handleProxyClusterInfo(Request request) {
        log.debug("PROXY CLUSTER INFO");
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            return RedisToken.string(client.clusterInfo().get());
        } catch (Exception e) {
            log.error("PROXY CLUSTER INFO ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }

    private RedisToken handleProxyConfig(Request request) {
        if (request.getLength() < 3) {
            log.debug("PROXY CONFIG ERR wrong number of arguments");
            return RedisToken.error("ERR wrong number of arguments for 'proxy config' command");
        }
        String action = request.getParam(1).toString().toUpperCase();
        String key = request.getParam(2).toString();
        log.debug("PROXY CONFIG action={} key={}", action, key);

        if ("GET".equals(action)) {
            String value = getProxyConfig(key);
            if (value != null) {
                log.debug("PROXY CONFIG GET OK value={}", value);
                return RedisToken.array(RedisToken.string(key), RedisToken.string(value));
            } else {
                return RedisToken.array();
            }
        } else if ("SET".equals(action) && request.getLength() >= 4) {
            String value = request.getParam(3).toString();
            setProxyConfig(key, value);
            log.debug("PROXY CONFIG SET OK value={}", value);
            return RedisToken.status("OK");
        } else {
            log.debug("PROXY CONFIG ERR wrong number of arguments");
            return RedisToken.error("ERR wrong number of arguments for 'proxy config' command");
        }
    }

    private String getProxyConfig(String key) {
        return proxyConfig.getOrDefault(key, defaultConfig.get(key));
    }

    private void setProxyConfig(String key, String value) {
        proxyConfig.put(key, value);
    }

    private RedisToken handleProxyStats() {
        log.debug("PROXY STATS");
        String stats = """
                cluster_nodes: 6\r
                cluster_state: ok\r
                proxy_uptime: running\r
                """;
        return RedisToken.string(stats);
    }

    private RedisToken handleProxyFlushClients() {
        log.debug("PROXY FLUSHCLIENTS");
        try {
            glideClientCache.closeAll();
            log.debug("PROXY FLUSHCLIENTS OK");
            return RedisToken.status("Flushed all clients");
        } catch (Exception e) {
            log.error("PROXY FLUSHCLIENTS ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }

    private RedisToken handleProxyClientInfo(Request request) {
        String clientId = request.getParam(1).toString();
        log.debug("PROXY CLIENTINFO clientId={}", clientId);
        return RedisToken.string("Client " + clientId + " info");
    }
}
