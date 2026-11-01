/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValkeywayPropertiesTest {

    private static ValkeywayProperties bind(Map<String, String> extra) {
        Map<String, String> map = new HashMap<>(Map.of(
                "proxy.port", "6379",
                "proxy.cluster-nodes[0]", "127.0.0.1:7000"));
        map.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(map)).bind("proxy", ValkeywayProperties.class).get();
    }

    @Test
    void requestTimeoutDefaultsTo2000() {
        assertEquals(2000, bind(Map.of()).requestTimeoutMs());
    }

    @Test
    void requestTimeoutIsUsedAsSet() {
        assertEquals(3500, bind(Map.of("proxy.request-timeout-ms", "3500")).requestTimeoutMs());
    }

    @Test
    void oldConnectTimeoutNameStillWorks() {
        ValkeywayProperties props = bind(Map.of("proxy.connect-timeout-ms", "1500"));
        assertEquals(1500, props.requestTimeoutMs());
    }

    @Test
    void newNameWinsOverOldName() {
        ValkeywayProperties props = bind(Map.of("proxy.connect-timeout-ms", "1500", "proxy.request-timeout-ms", "4000"));
        assertEquals(4000, props.requestTimeoutMs());
    }

    @Test
    void requestTimeoutHasAFloor() {
        assertEquals(500, bind(Map.of("proxy.request-timeout-ms", "10")).requestTimeoutMs());
    }
}
