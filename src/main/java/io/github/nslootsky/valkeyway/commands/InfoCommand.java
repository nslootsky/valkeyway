/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import glide.api.models.configuration.RequestRoutingConfiguration.SimpleMultiNodeRoute;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.ops.MetricsCollector;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * INFO, as a standalone server would answer it, built from the cluster and the proxy's own state:
 * <ul>
 *   <li>clients, replication and cluster: the proxy's view (its clients; a primary without
 *       replicas; cluster support disabled), which is what clients that detect standalone mode
 *       (Glide among them) look at;</li>
 *   <li>stats, keyspace, commandstats and errorstats: summed over the primaries, except the
 *       proxy's own command and error counts;</li>
 *   <li>everything else (server, memory, persistence, ...): one primary's, with standalone mode and
 *       the proxy's port.</li>
 * </ul>
 * If the cluster can't be reached, only the proxy's sections are answered.
 */
// [upstream:V10]
public class InfoCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(InfoCommand.class);
    private static final long TIMEOUT_SECONDS = 5;

    private final BackendClients backendClients;
    private final MetricsCollector metrics;
    private final WatchSessions watches;

    public InfoCommand(BackendClients backendClients, MetricsCollector metrics, WatchSessions watches) {
        this.backendClients = backendClients;
        this.metrics = metrics;
        this.watches = watches;
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    /** One node's INFO, by section (lowercase name) and field, in the node's order. */
    record Sections(Map<String, String> titles, Map<String, Map<String, String>> fields) {}

    @Override
    public Reply execute(Request request) throws InterruptedException {
        Connection connection = request.connection();
        List<Sections> nodes = fromCluster(request);
        List<String> order = new ArrayList<>();
        Map<String, String> titles = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            for (String section : List.of("clients", "stats", "replication", "cluster")) {
                order.add(section);
                titles.put(section, Character.toUpperCase(section.charAt(0)) + section.substring(1));
            }
        } else {
            titles.putAll(nodes.getFirst().titles());
            order.addAll(nodes.getFirst().fields().keySet());
        }
        StringBuilder out = new StringBuilder();
        for (String section : order) {
            Map<String, String> fields = switch (section) {
                case "server" -> server(nodes.getFirst().fields().get(section), connection);
                case "clients" -> clients(connection);
                case "replication" -> replication();
                case "cluster" -> Map.of("cluster_enabled", "0");
                case "stats" -> stats(nodes);
                case "keyspace" -> keyspace(nodes);
                case "persistence" -> persistence(nodes);
                case "commandstats", "errorstats" -> sumPairs(nodes, section);
                default -> nodes.getFirst().fields().get(section);
            };
            if (!out.isEmpty()) {
                out.append("\r\n");
            }
            out.append("# ").append(titles.getOrDefault(section, section)).append("\r\n");
            fields.forEach((k, v) -> out.append(k).append(':').append(v).append("\r\n"));
        }
        return new Reply.Verbatim(out.toString());
    }

    /** INFO with the request's sections from every primary, in address order; empty if unreachable. */
    private List<Sections> fromCluster(Request request) throws InterruptedException {
        byte[][] args = request.args();
        GlideString[] command = new GlideString[args.length];
        command[0] = GlideString.of("INFO");
        for (int i = 1; i < args.length; i++) {
            command[i] = GlideString.of(args[i]);
        }
        try {
            GlideClusterClient client = backendClients.getClient(request.connection().db());
            ClusterValue<Object> result = client.customCommand(command, SimpleMultiNodeRoute.ALL_PRIMARIES)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<String, Object> perNode = result.hasMultiData() ? new TreeMap<>(result.getMultiValue())
                    : Map.of("", result.getSingleValue());
            List<Sections> nodes = new ArrayList<>();
            for (Object text : perNode.values()) {
                nodes.add(parse(String.valueOf(text)));
            }
            return nodes;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.debug("INFO from the cluster failed, answering the proxy's sections only: {}", e.getMessage());
            return List.of();
        }
    }

    static Sections parse(String text) {
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, Map<String, String>> fields = new LinkedHashMap<>();
        Map<String, String> current = null;
        for (String line : text.split("\r?\n")) {
            if (line.startsWith("# ")) {
                String title = line.substring(2).trim();
                String key = title.toLowerCase(Locale.ROOT);
                titles.put(key, title);
                current = fields.computeIfAbsent(key, _ -> new LinkedHashMap<>());
            } else if (current != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    current.put(line.substring(0, colon), line.substring(colon + 1));
                }
            }
        }
        return new Sections(titles, fields);
    }

    /** A node's server section, as the proxy's: standalone mode, and the proxy's port and listener. */
    private static Map<String, String> server(Map<String, String> node, Connection connection) {
        var local = connection.localAddress();
        Map<String, String> fields = new LinkedHashMap<>();
        node.forEach((k, v) -> {
            if (!k.startsWith("listener")) {
                fields.put(k, v);
            }
        });
        fields.replace("redis_mode", "standalone");
        fields.replace("server_mode", "standalone");
        fields.replace("tcp_port", Integer.toString(local.getPort()));
        if (node.containsKey("listener0")) {
            // The address this client connected to (the proxy's bind address isn't known here).
            fields.put("listener0", "name=" + (connection.tls() ? "tls" : "tcp") + ",bind="
                    + local.getAddress().getHostAddress() + ",port=" + local.getPort());
        }
        return fields;
    }

    private Map<String, String> clients(Connection self) {
        int connected = 0, blocked = 0, pubsub = 0, watching = 0, watchedKeys = 0;
        Set<ByteBuffer> blockingKeys = new HashSet<>();
        Set<ByteBuffer> noKeyKeys = new HashSet<>();
        for (Connection c : self.registry().clients()) {
            connected++;
            List<byte[]> keys = c.blockedOn();
            if (keys != null) {
                blocked++;
                keys.forEach(k -> blockingKeys.add(ByteBuffer.wrap(k)));
                if (c.blockedOnNoKey()) {
                    keys.forEach(k -> noKeyKeys.add(ByteBuffer.wrap(k)));
                }
            }
            if (c.subscribed()) {
                pubsub++;
            }
            var watch = watches.current(c);
            if (watch != null) {
                watching++;
                watchedKeys += watch.keyCount();
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("connected_clients", Integer.toString(connected));
        fields.put("cluster_connections", "0");
        fields.put("maxclients", Integer.toString(self.registry().maxClients()));
        fields.put("blocked_clients", Integer.toString(blocked));
        fields.put("tracking_clients", "0");
        fields.put("pubsub_clients", Integer.toString(pubsub));
        fields.put("watching_clients", Integer.toString(watching));
        fields.put("clients_in_timeout_table", Integer.toString(blocked));
        fields.put("total_watched_keys", Integer.toString(watchedKeys));
        fields.put("total_blocking_keys", Integer.toString(blockingKeys.size()));
        fields.put("total_blocking_keys_on_nokey", Integer.toString(noKeyKeys.size()));
        return fields;
    }

    private static Map<String, String> replication() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("role", "master");
        fields.put("connected_slaves", "0");
        fields.put("replicas_waiting_psync", "0");
        fields.put("master_failover_state", "no-failover");
        fields.put("master_replid", "0000000000000000000000000000000000000000");
        fields.put("master_replid2", "0000000000000000000000000000000000000000");
        fields.put("master_repl_offset", "0");
        fields.put("second_repl_offset", "-1");
        fields.put("repl_backlog_active", "0");
        fields.put("repl_backlog_size", "0");
        fields.put("repl_backlog_first_byte_offset", "0");
        fields.put("repl_backlog_histlen", "0");
        return fields;
    }

    /** Integer fields summed over the primaries, the rest from the first; the proxy's own counts. */
    private Map<String, String> stats(List<Sections> nodes) {
        Map<String, String> fields = sum(nodes, "stats");
        fields.put("total_commands_processed", Long.toString((long) metrics.getTotalCommands()));
        fields.put("total_error_replies", Long.toString((long) metrics.getTotalErrors()));
        return fields;
    }

    static Map<String, String> sum(List<Sections> nodes, String section) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            return fields;
        }
        for (Map.Entry<String, String> field : nodes.getFirst().fields().getOrDefault(section, Map.of()).entrySet()) {
            String value = field.getValue();
            try {
                long total = 0;
                for (Sections node : nodes) {
                    total += Long.parseLong(node.fields().getOrDefault(section, Map.of()).getOrDefault(field.getKey(), "0"));
                }
                value = Long.toString(total);
            } catch (NumberFormatException e) {
                // not a counter: keep the first node's value
            }
            fields.put(field.getKey(), value);
        }
        return fields;
    }

    /**
     * Sections of {@code name:k=v,k=v} lines (commandstats, errorstats), summed per field and key over
     * the primaries; a field may exist on some nodes only. Averages ({@code usec_per_call}) are
     * recomputed rather than summed.
     */
    static Map<String, String> sumPairs(List<Sections> nodes, String section) {
        Map<String, Map<String, Double>> totals = new TreeMap<>();
        for (Sections node : nodes) {
            for (Map.Entry<String, String> field : node.fields().getOrDefault(section, Map.of()).entrySet()) {
                Map<String, Double> sum = totals.computeIfAbsent(field.getKey(), _ -> new LinkedHashMap<>());
                for (String pair : field.getValue().split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        try {
                            sum.merge(pair.substring(0, eq), Double.parseDouble(pair.substring(eq + 1)), Double::sum);
                        } catch (NumberFormatException e) {
                            // not a number: skip
                        }
                    }
                }
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        totals.forEach((name, sum) -> {
            if (sum.containsKey("usec_per_call") && sum.containsKey("usec") && sum.containsKey("calls")) {
                double calls = sum.get("calls");
                sum.put("usec_per_call", calls == 0 ? 0 : sum.get("usec") / calls);
            }
            StringBuilder value = new StringBuilder();
            sum.forEach((k, v) -> value.append(value.isEmpty() ? "" : ",").append(k).append('=')
                    .append(k.endsWith("_per_call") ? String.format(Locale.ROOT, "%.2f", v) : Long.toString(Math.round(v))));
            fields.put(name, value.toString());
        });
        return fields;
    }

    /** One primary's persistence section, with the count of unsaved changes summed over all of them. */
    private static Map<String, String> persistence(List<Sections> nodes) {
        Map<String, String> fields = new LinkedHashMap<>(nodes.getFirst().fields().get("persistence"));
        String dirty = sum(nodes, "persistence").get("rdb_changes_since_last_save");
        if (dirty != null) {
            fields.put("rdb_changes_since_last_save", dirty);
        }
        return fields;
    }

    /** Per database: key and expire counts summed, avg_ttl weighted by each node's expiring keys. */
    static Map<String, String> keyspace(List<Sections> nodes) {
        Map<String, Map<String, Long>> dbs = new TreeMap<>(Comparator.comparingInt(InfoCommand::dbNumber));
        Map<String, Long> ttlWeight = new LinkedHashMap<>();
        for (Sections node : nodes) {
            for (Map.Entry<String, String> db : node.fields().getOrDefault("keyspace", Map.of()).entrySet()) {
                Map<String, Long> totals = dbs.computeIfAbsent(db.getKey(), _ -> new LinkedHashMap<>());
                Map<String, Long> values = new LinkedHashMap<>();
                for (String pair : db.getValue().split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        try {
                            values.put(pair.substring(0, eq), Long.parseLong(pair.substring(eq + 1)));
                        } catch (NumberFormatException e) {
                            // unknown format: skip the field
                        }
                    }
                }
                long expires = values.getOrDefault("expires", 0L);
                ttlWeight.merge(db.getKey(), values.getOrDefault("avg_ttl", 0L) * expires, Long::sum);
                values.forEach((k, v) -> totals.merge(k, k.equals("avg_ttl") ? 0 : v, Long::sum));
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        dbs.forEach((db, totals) -> {
            long expires = totals.getOrDefault("expires", 0L);
            if (totals.containsKey("avg_ttl")) {
                totals.put("avg_ttl", expires == 0 ? 0 : ttlWeight.get(db) / expires);
            }
            StringBuilder value = new StringBuilder();
            totals.forEach((k, v) -> value.append(value.isEmpty() ? "" : ",").append(k).append('=').append(v));
            if (totals.getOrDefault("keys", 0L) > 0) {
                fields.put(db, value.toString());
            }
        });
        return fields;
    }

    private static int dbNumber(String name) {
        try {
            return Integer.parseInt(name.substring(2));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
