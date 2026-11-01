/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The proxy's version, from the build (META-INF/build-info.properties, written by the Spring Boot plugin). */
public final class ProxyVersion {

    private static final String VERSION = load();

    private ProxyVersion() {}

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = ProxyVersion.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("build.version", "unknown");
            }
        } catch (IOException e) {
            // fall through
        }
        return "unknown";
    }
}
