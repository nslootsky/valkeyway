/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.ProxyVersion;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.ops.MetricsCollector;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * PROXY: the proxy's own administration.
 * <ul>
 *   <li>{@code PROXY STATS}: clients, commands, errors and configuration, as INFO-style text.</li>
 *   <li>{@code PROXY CLIENTINFO <id>}: one client, in CLIENT LIST format.</li>
 *   <li>{@code PROXY CONFIG GET <name|*>}: the effective settings. They are read at startup, so
 *       there is no CONFIG SET.</li>
 *   <li>{@code PROXY CLUSTER INFO}: the cluster's own CLUSTER INFO (plain CLUSTER INFO answers like a
 *       standalone server).</li>
 * </ul>
 */
public class ValkeywayAdminCommand implements CommandHandler {

    private final BackendClients backendClients;
    private final MetricsCollector metrics;
    private final ClientCommand clients;
    private final Map<String, String> config;

    public ValkeywayAdminCommand(BackendClients backendClients, MetricsCollector metrics, ClientCommand clients,
                                 ValkeywayProperties props) {
        this.backendClients = backendClients;
        this.metrics = metrics;
        this.clients = clients;
        this.config = effectiveConfig(props);
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'proxy' command");
        }
        String sub = request.argString(0).toLowerCase(Locale.ROOT);
        int n = request.argCount();
        return switch (sub) {
            case "stats" -> n == 1 ? stats(request.connection()) : arity(sub);
            case "clientinfo" -> n == 2 ? clientInfo(request) : arity(sub);
            case "config" -> n == 3 && request.argString(1).equalsIgnoreCase("get") ? configGet(request.argString(2))
                    : n >= 2 && request.argString(1).equalsIgnoreCase("set")
                    ? Reply.error("ERR PROXY CONFIG SET is not supported: settings are read at startup")
                    : arity("config");
            case "cluster" -> n == 2 && request.argString(1).equalsIgnoreCase("info") ? clusterInfo(request) : arity(sub);
            case "help" -> help();
            default -> Reply.error("ERR unknown subcommand '" + request.argString(0) + "'. Try PROXY HELP.");
        };
    }

    private static Reply arity(String sub) {
        return Reply.error("ERR wrong number of arguments for 'proxy|" + sub + "' command");
    }

    private Reply stats(Connection connection) {
        long uptimeSeconds = TimeUnit.MILLISECONDS.toSeconds(ManagementFactory.getRuntimeMXBean().getUptime());
        String text = "# Proxy\r\n"
                + "valkeyway_version:" + ProxyVersion.get() + "\r\n"
                + "uptime_in_seconds:" + uptimeSeconds + "\r\n"
                + "connected_clients:" + connection.registry().clients().size() + "\r\n"
                + "maxclients:" + connection.registry().maxClients() + "\r\n"
                + "total_commands_processed:" + (long) metrics.getTotalCommands() + "\r\n"
                + "total_error_replies:" + (long) metrics.getTotalErrors() + "\r\n"
                + "\r\n# Config\r\n"
                + configLines();
        return new Reply.Verbatim(text);
    }

    private String configLines() {
        StringBuilder out = new StringBuilder();
        config.forEach((name, value) -> out.append(name.replace('-', '_').replace('.', '_'))
                .append(':').append(value).append("\r\n"));
        return out.toString();
    }

    private Reply clientInfo(Request request) {
        long id;
        try {
            id = Long.parseLong(request.argString(1));
        } catch (NumberFormatException e) {
            return Reply.error("ERR syntax error");
        }
        Connection client = request.connection().registry().find(id);
        return client == null ? Reply.error("ERR No such client") : new Reply.Verbatim(clients.line(client) + "\n");
    }

    private Reply configGet(String pattern) {
        List<Reply> pairs = new ArrayList<>();
        config.forEach((name, value) -> {
            if (pattern.equals("*") || pattern.equalsIgnoreCase(name)) {
                pairs.add(Reply.bulk(name));
                pairs.add(Reply.bulk(value));
            }
        });
        return new Reply.Array(pairs);
    }

    private Reply clusterInfo(Request request) {
        try {
            GlideClusterClient client = backendClients.getClient(request.connection().db());
            return new Reply.Verbatim(client.clusterInfo().get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            return ServerErrors.error(e);
        }
    }

    /** The settings in effect, by their property names under {@code proxy.}. */
    private static Map<String, String> effectiveConfig(ValkeywayProperties props) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("host", props.host());
        c.put("port", Integer.toString(props.port()));
        c.put("cluster-nodes", String.join(",", props.clusterNodes()));
        c.put("request-timeout-ms", Long.toString(props.requestTimeoutMs()));
        c.put("clients.max-clients", Integer.toString(props.clients().maxClients()));
        c.put("clients.timeout-seconds", Long.toString(props.clients().timeoutSeconds()));
        c.put("clients.io-threads", Integer.toString(props.clients().ioThreads()));
        c.put("glide.inflight-requests-limit", Integer.toString(props.glide().inflightRequestsLimit()));
        c.put("glide.coalesce-requests", Boolean.toString(props.glide().coalesceRequests()));
        c.put("glide.refresh-topology-from-seeds", Boolean.toString(props.glide().refreshTopologyFromSeeds()));
        c.put("glide.topology-check-seconds", Integer.toString(props.glide().topologyCheckSeconds()));
        c.put("blocking-pool.max-size", Integer.toString(props.blockingPool().maxSize()));
        c.put("blocking-pool.idle-timeout-seconds", Long.toString(props.blockingPool().idleTimeoutSeconds()));
        c.put("transactions.cross-slot", props.transactions().crossSlot().name().toLowerCase(Locale.ROOT));
        c.put("transactions.watch-pool.max-size", Integer.toString(props.transactions().watchPool().maxSize()));
        c.put("node-admin-commands", props.nodeAdminCommands().name().toLowerCase(Locale.ROOT));
        // Passwords are never shown, only whether they are set.
        // Whether the default user really has a password (also when it comes from auth.users.default).
        String defaultPassword = props.auth().users().getOrDefault("default", props.auth().password());
        c.put("auth.password", defaultPassword == null || defaultPassword.isEmpty() ? "" : "(set)");
        c.put("auth.users", String.join(",", new TreeSet<>(props.auth().users().keySet())));
        c.put("tls.port", Integer.toString(props.tls().port()));
        c.put("tls.client-certs", props.tls().clientCerts().name().toLowerCase(Locale.ROOT));
        c.put("backend.username", props.backend().username() == null ? "" : props.backend().username());
        c.put("backend.password", props.backend().password() == null ? "" : "(set)");
        c.put("backend.tls.enabled", Boolean.toString(props.backend().tls().enabled()));
        c.put("backend.tls.insecure", Boolean.toString(props.backend().tls().insecure()));
        return c;
    }

    private static Reply help() {
        String[] lines = {
                "PROXY <subcommand> [<arg> ...]. Subcommands are:",
                "STATS", "    Clients, commands, errors and configuration.",
                "CLIENTINFO <id>", "    One client, in CLIENT LIST format.",
                "CONFIG GET <name|*>", "    The settings in effect (read at startup; there is no CONFIG SET).",
                "CLUSTER INFO", "    The cluster's own CLUSTER INFO.",
                "HELP", "    Print this help.",
        };
        List<Reply> replies = new ArrayList<>(lines.length);
        for (String line : lines) {
            replies.add(new Reply.Simple(line));
        }
        return new Reply.Array(replies);
    }
}
