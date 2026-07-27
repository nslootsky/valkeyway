package io.github.nslootsky.proxy.integration;

import glide.api.GlideClient;
import glide.api.models.commands.SetOptions;
import glide.api.models.commands.scan.ScanOptions;
import glide.api.models.configuration.GlideClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.NodeDiscoveryMode;
import glide.api.models.configuration.ProtocolVersion;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class GlideTestClient implements AutoCloseable {

    private final GlideClient client;

    public GlideTestClient(String host, int port) throws Exception {
        GlideClientConfiguration config = GlideClientConfiguration.builder()
                .address(NodeAddress.builder().host(host).port(port).build())
                .requestTimeout(5000)
                .protocol(ProtocolVersion.RESP2)
                .nodeDiscoveryMode(NodeDiscoveryMode.STATIC)
                .build();
        this.client = GlideClient.createClient(config).get(10, TimeUnit.SECONDS);
    }

    public GlideTestClient(String host, int port, int databaseId) throws Exception {
        GlideClientConfiguration config = GlideClientConfiguration.builder()
                .address(NodeAddress.builder().host(host).port(port).build())
                .databaseId(databaseId)
                .requestTimeout(5000)
                .protocol(ProtocolVersion.RESP2)
                .nodeDiscoveryMode(NodeDiscoveryMode.STATIC)
                .build();
        this.client = GlideClient.createClient(config).get(10, TimeUnit.SECONDS);
    }

    public String set(String key, String value) throws Exception {
        return client.set(key, value).get(5, TimeUnit.SECONDS);
    }

    public String set(String key, String value, long seconds) throws Exception {
        SetOptions options = SetOptions.builder()
                .expiry(SetOptions.Expiry.Seconds(seconds))
                .build();
        return client.set(key, value, options).get(5, TimeUnit.SECONDS);
    }

    public String get(String key) throws Exception {
        return client.get(key).get(5, TimeUnit.SECONDS);
    }

    public Long del(String... keys) throws Exception {
        return client.del(keys).get(5, TimeUnit.SECONDS);
    }

    public Long unlink(String... keys) throws Exception {
        return client.unlink(keys).get(5, TimeUnit.SECONDS);
    }

    public String[] mget(String... keys) throws Exception {
        return client.mget(keys).get(5, TimeUnit.SECONDS);
    }

    public String ping() throws Exception {
        return client.ping().get(5, TimeUnit.SECONDS);
    }

    public String info() throws Exception {
        return client.info().get(5, TimeUnit.SECONDS);
    }

    public String[] time() throws Exception {
        return client.time().get(5, TimeUnit.SECONDS);
    }

    public Boolean expire(String key, long seconds) throws Exception {
        return client.expire(key, seconds).get(5, TimeUnit.SECONDS);
    }

    public Long ttl(String key) throws Exception {
        return client.ttl(key).get(5, TimeUnit.SECONDS);
    }

    public Long exists(String... keys) throws Exception {
        return client.exists(keys).get(5, TimeUnit.SECONDS);
    }

    public String select(long index) throws Exception {
        return client.select(index).get(5, TimeUnit.SECONDS);
    }

    public Long hset(String key, String field, String value) throws Exception {
        Map<String, String> map = new HashMap<>();
        map.put(field, value);
        return client.hset(key, map).get(5, TimeUnit.SECONDS);
    }

    public String hget(String key, String field) throws Exception {
        return client.hget(key, field).get(5, TimeUnit.SECONDS);
    }

    public Long hdel(String key, String... fields) throws Exception {
        return client.hdel(key, fields).get(5, TimeUnit.SECONDS);
    }

    public Map<String, String> hgetall(String key) throws Exception {
        return client.hgetall(key).get(5, TimeUnit.SECONDS);
    }

    public String multi() throws Exception {
        return (String) client.customCommand(new String[]{"MULTI"}).get(5, TimeUnit.SECONDS);
    }

    public String discard() throws Exception {
        return (String) client.customCommand(new String[]{"DISCARD"}).get(5, TimeUnit.SECONDS);
    }

    public Object[] exec() throws Exception {
        return (Object[]) client.customCommand(new String[]{"EXEC"}).get(5, TimeUnit.SECONDS);
    }

    public Object[] scan(String cursor) throws Exception {
        return client.scan(cursor).get(5, TimeUnit.SECONDS);
    }

    public Object[] scan(String cursor, String matchPattern) throws Exception {
        ScanOptions options = ScanOptions.builder()
                .matchPattern(matchPattern)
                .count(100L)
                .build();
        return client.scan(cursor, options).get(5, TimeUnit.SECONDS);
    }

    public String proxyFlushClients() throws Exception {
        return (String) client.customCommand(new String[]{"PROXY", "FLUSHCLIENTS"}).get(5, TimeUnit.SECONDS);
    }

    public String proxyClientInfo(String clientId) throws Exception {
        return (String) client.customCommand(new String[]{"PROXY", "CLIENTINFO", clientId}).get(5, TimeUnit.SECONDS);
    }

    public String customCommand(String... args) throws Exception {
        return (String) client.customCommand(args).get(5, TimeUnit.SECONDS);
    }

    public Object[] customCommandArr(String... args) throws Exception {
        return (Object[]) client.customCommand(args).get(5, TimeUnit.SECONDS);
    }

    public GlideClient getClient() {
        return client;
    }

    @Override
    public void close() throws ExecutionException {
        client.close();
    }
}
