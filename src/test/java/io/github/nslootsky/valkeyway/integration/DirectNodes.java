/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
