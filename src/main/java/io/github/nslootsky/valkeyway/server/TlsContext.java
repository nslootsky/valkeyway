/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The server's TLS settings, built from PEM files. {@link #reloadIfChanged()} rebuilds the context
 * when a file has changed (certificate rotation); connections made from then on use the new
 * certificate, existing ones keep theirs.
 */
public final class TlsContext {

    private static final Logger log = LoggerFactory.getLogger(TlsContext.class);

    /** How clients' certificates are checked, like Valkey's {@code tls-auth-clients}. */
    public enum ClientCerts { NONE, OPTIONAL, REQUIRED }

    /**
     * @param certFile  the server's certificate chain (PEM)
     * @param keyFile   its private key (PEM, PKCS#8: {@code BEGIN PRIVATE KEY} or {@code BEGIN ENCRYPTED PRIVATE KEY})
     * @param keyPassword the key's password, or {@code null}
     * @param caFile    CA certificates that sign client certificates (PEM), or {@code null}
     * @param clientCerts whether clients must present a certificate signed by {@code caFile}
     * @param protocols TLS versions to accept (e.g. TLSv1.2, TLSv1.3); empty for the JDK's defaults
     */
    public record Settings(Path certFile, Path keyFile, String keyPassword, Path caFile, ClientCerts clientCerts,
                           List<String> protocols) {
        public Settings {
            Objects.requireNonNull(certFile, "TLS needs a certificate file");
            Objects.requireNonNull(keyFile, "TLS needs a key file");
            if (clientCerts == null) clientCerts = ClientCerts.NONE;
            if (protocols == null) protocols = List.of();
            if (clientCerts != ClientCerts.NONE && caFile == null) {
                throw new IllegalArgumentException("Checking client certificates needs a CA file");
            }
        }

        private List<Path> files() {
            List<Path> files = new ArrayList<>(List.of(certFile, keyFile));
            if (caFile != null) {
                files.add(caFile);
            }
            return files;
        }
    }

    private final Settings settings;
    private volatile SslContext context;
    private volatile List<FileTime> loadedVersions;

    public TlsContext(Settings settings) throws IOException {
        this.settings = settings;
        this.loadedVersions = versions();
        this.context = build(settings);
    }

    /** The context for new connections. */
    public SslContext current() {
        return context;
    }

    /** Rebuilds the context if any of its files changed; on a bad file, keeps the old one. */
    public void reloadIfChanged() {
        List<FileTime> versions;
        try {
            versions = versions();
        } catch (IOException e) {
            log.warn("Can't check the TLS files for changes: {}", e.getMessage());
            return;
        }
        if (versions.equals(loadedVersions)) {
            return;
        }
        try {
            context = build(settings);
            loadedVersions = versions;
            log.info("Reloaded the TLS certificate from {}", settings.certFile());
        } catch (Exception e) {
            // Likely caught mid-rotation (key written, certificate not yet): try again next time.
            log.warn("Keeping the current TLS certificate; the new files can't be used yet: {}", e.getMessage());
        }
    }

    private List<FileTime> versions() throws IOException {
        List<FileTime> versions = new ArrayList<>();
        for (Path file : settings.files()) {
            versions.add(Files.getLastModifiedTime(file));
        }
        return versions;
    }

    static SslContext build(Settings settings) throws SSLException {
        SslContextBuilder builder = SslContextBuilder.forServer(
                settings.certFile().toFile(), settings.keyFile().toFile(), settings.keyPassword());
        if (settings.caFile() != null) {
            builder.trustManager(settings.caFile().toFile());
        }
        builder.clientAuth(switch (settings.clientCerts()) {
            case NONE -> ClientAuth.NONE;
            case OPTIONAL -> ClientAuth.OPTIONAL;
            case REQUIRED -> ClientAuth.REQUIRE;
        });
        if (!settings.protocols().isEmpty()) {
            builder.protocols(settings.protocols());
        }
        return builder.build();
    }
}
