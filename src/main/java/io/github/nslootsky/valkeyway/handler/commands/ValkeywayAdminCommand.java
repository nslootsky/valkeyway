/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROXY admin command: CLUSTER INFO, CONFIG GET/SET, STATS, FLUSHCLIENTS, CLIENTINFO.
 */
public class ValkeywayAdminCommand implements CommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

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
    public Reply execute(Request request) {
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'proxy' command");
        }
        String subCmd = request.argString(0).toUpperCase();
        if ("CLUSTER".equals(subCmd) && request.argCount() >= 2 && "INFO".equalsIgnoreCase(request.argString(1))) {
            return handleProxyClusterInfo(request);
        } else if ("CONFIG".equals(subCmd) && request.argCount() >= 2) {
            return handleProxyConfig(request);
        } else if ("STATS".equals(subCmd)) {
            return handleProxyStats();
        } else if ("FLUSHCLIENTS".equals(subCmd)) {
            return handleProxyFlushClients();
        } else if ("CLIENTINFO".equals(subCmd) && request.argCount() >= 2) {
            return Reply.bulk("Client " + request.argString(1) + " info");
        }
        return Reply.error("ERR unknown proxy subcommand");
    }

    private Reply handleProxyClusterInfo(Request request) {
        try {
            GlideClusterClient client = glideClientCache.getClient(request.connection().db());
            return Reply.bulk(client.clusterInfo().get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            return ServerErrors.error(e);
        }
    }

    private Reply handleProxyConfig(Request request) {
        if (request.argCount() < 3) {
            return Reply.error("ERR wrong number of arguments for 'proxy config' command");
        }
        String action = request.argString(1).toUpperCase();
        String key = request.argString(2);
        if ("GET".equals(action)) {
            String value = proxyConfig.getOrDefault(key, defaultConfig.get(key));
            return value != null ? Reply.array(Reply.bulk(key), Reply.bulk(value)) : Reply.EMPTY_ARRAY;
        } else if ("SET".equals(action) && request.argCount() >= 4) {
            proxyConfig.put(key, request.argString(3));
            return Reply.OK;
        }
        return Reply.error("ERR wrong number of arguments for 'proxy config' command");
    }

    private Reply handleProxyStats() {
        return Reply.bulk("""
                cluster_nodes: 6\r
                cluster_state: ok\r
                proxy_uptime: running\r
                """);
    }

    private Reply handleProxyFlushClients() {
        glideClientCache.closeAll();
        return new Reply.Simple("Flushed all clients");
    }
}
