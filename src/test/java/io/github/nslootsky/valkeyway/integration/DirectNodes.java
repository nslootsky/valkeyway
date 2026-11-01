/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Raw connections straight to the cluster's nodes, bypassing the proxy.
 */
class DirectNodes implements AutoCloseable {

    private final List<String> addresses;
    private final Map<String, RawRespClient> clients = new HashMap<>();

    DirectNodes(List<String> addresses) {
        this.addresses = addresses;
    }

    List<String> addresses() {
        return addresses;
    }

    /** Sends a command to the node owning its keys, following MOVED redirects. */
    Object call(String... cmd) throws IOException {
        String address = addresses.getFirst();
        for (int attempt = 0; attempt < 3; attempt++) {
            Object reply = at(address).call(cmd);
            if (reply instanceof Error(String message) && message.startsWith("MOVED ")) {
                address = message.split(" ")[2];
                continue;
            }
            return reply;
        }
        throw new IOException("Too many redirects for " + String.join(" ", cmd));
    }

    /** The address of the node currently serving {@code key}. */
    String ownerOf(String key) throws IOException {
        String address = addresses.getFirst();
        for (int attempt = 0; attempt < 3; attempt++) {
            Object reply = at(address).call("EXISTS", key);
            if (reply instanceof Error(String message) && message.startsWith("MOVED ")) {
                address = message.split(" ")[2];
                continue;
            }
            return address;
        }
        throw new IOException("Too many redirects for " + key);
    }

    /** One line of CLUSTER NODES. */
    record Node(String id, String address, boolean primary, String primaryId, boolean failing) {}

    /** The cluster as {@code viewFrom} sees it. */
    List<Node> nodes(String viewFrom) throws IOException {
        List<Node> nodes = new ArrayList<>();
        for (String line : ((String) at(viewFrom).call("CLUSTER", "NODES")).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.trim().split(" ");
            List<String> flags = Arrays.asList(f[2].split(","));
            String address = f[1].split("@")[0];
            nodes.add(new Node(f[0], address, flags.contains("master"), f[3].equals("-") ? null : f[3],
                    flags.contains("fail") || flags.contains("fail?")));
        }
        return nodes;
    }

    String nodeId(String address) throws IOException {
        return (String) at(address).call("CLUSTER", "MYID");
    }

    /** Whether {@code address} currently considers itself a primary. */
    boolean isPrimary(String address) throws IOException {
        return "master".equals(((List<?>) at(address).call("ROLE")).getFirst());
    }

    /** The address of a replica of the primary at {@code primary}. */
    String replicaOf(String primary) throws IOException {
        String id = nodeId(primary);
        return nodes(primary).stream().filter(n -> id.equals(n.primaryId())).map(Node::address)
                .findFirst().orElseThrow(() -> new IllegalStateException(primary + " has no replica"));
    }

    /** Live primaries, as seen from the first address. */
    List<String> primaries() throws IOException {
        return nodes(addresses.getFirst()).stream().filter(n -> n.primary() && !n.failing()).map(Node::address).toList();
    }

    /** Marks {@code slot} as moving from {@code source} to {@code target}; no keys move yet. */
    void beginMigration(long slot, String source, String target) throws IOException {
        String s = Long.toString(slot);
        expectOk(at(target).call("CLUSTER", "SETSLOT", s, "IMPORTING", nodeId(source)));
        expectOk(at(source).call("CLUSTER", "SETSLOT", s, "MIGRATING", nodeId(target)));
    }

    /** Moves the given keys of a migrating slot. */
    void migrateKeys(String source, String target, List<String> keys) throws IOException {
        String[] hostPort = target.split(":");
        List<String> migrate = new ArrayList<>(List.of("MIGRATE", hostPort[0], hostPort[1], "", "0", "5000", "KEYS"));
        migrate.addAll(keys);
        expectOk(at(source).call(migrate.toArray(String[]::new)));
    }

    /** Moves the remaining keys of {@code slot} and assigns it to {@code target} on every primary. */
    void finishMigration(long slot, String source, String target) throws IOException {
        String s = Long.toString(slot);
        List<String> keys = ((List<?>) at(source).call("CLUSTER", "GETKEYSINSLOT", s, "1000")).stream()
                .map(String.class::cast).toList();
        if (!keys.isEmpty()) {
            migrateKeys(source, target, keys);
        }
        String targetId = nodeId(target);
        // Target first, then source, then the rest, as the cluster spec recommends.
        List<String> order = new ArrayList<>(List.of(target, source));
        primaries().stream().filter(a -> !order.contains(a)).forEach(order::add);
        for (String address : order) {
            expectOk(at(address).call("CLUSTER", "SETSLOT", s, "NODE", targetId));
        }
    }

    /** A full live migration of {@code slot}. */
    void moveSlot(long slot, String source, String target) throws IOException {
        beginMigration(slot, source, target);
        finishMigration(slot, source, target);
    }

    long slotOf(String key) throws IOException {
        return (Long) at(addresses.getFirst()).call("CLUSTER", "KEYSLOT", key);
    }

    private static void expectOk(Object reply) {
        if (!new Status("OK").equals(reply)) {
            throw new IllegalStateException("Expected OK, got " + reply);
        }
    }

    /** Polls {@code condition} until it holds, failing after {@code timeout}. */
    static void await(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A connection to one specific node. */
    RawRespClient at(String address) throws IOException {
        RawRespClient client = clients.get(address);
        if (client == null) {
            String[] hostPort = address.split(":");
            client = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]));
            clients.put(address, client);
        }
        return client;
    }

    @Override
    public void close() throws IOException {
        for (RawRespClient client : clients.values()) {
            client.close();
        }
        clients.clear();
    }
}
