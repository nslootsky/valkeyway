/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Throwaway certificates for TLS tests, made with the JDK's keytool once per test run: a CA, a server
 * certificate for {@code localhost} / {@code 127.0.0.1} and a client certificate, all signed by the CA.
 * Files are PEM ({@code ca.crt}, {@code server.crt}, {@code server.key}, {@code client.crt},
 * {@code client.key}) plus PKCS#12 keystores for Java clients.
 */
public final class TestCertificates {

    public static final String STORE_PASSWORD = "changeit";

    private static TestCertificates instance;

    private final Path dir;

    private TestCertificates(Path dir) {
        this.dir = dir;
    }

    public static synchronized TestCertificates get() {
        if (instance == null) {
            try {
                Path dir = Files.createTempDirectory("valkeyway-tls");
                dir.toFile().deleteOnExit();
                generate(dir);
                instance = new TestCertificates(dir);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("Can't generate test certificates", e);
            }
        }
        return instance;
    }

    public Path dir() {
        return dir;
    }

    public Path caCert() {
        return dir.resolve("ca.crt");
    }

    public Path serverCert() {
        return dir.resolve("server.crt");
    }

    public Path serverKey() {
        return dir.resolve("server.key");
    }

    public Path clientCert() {
        return dir.resolve("client.crt");
    }

    public Path clientKey() {
        return dir.resolve("client.key");
    }

    /** Sockets that trust the test CA and present no client certificate. */
    public SSLSocketFactory clientSockets() {
        return sockets(false);
    }

    /** Sockets that trust the test CA and present the client certificate. */
    public SSLSocketFactory clientSocketsWithCertificate() {
        return sockets(true);
    }

    private SSLSocketFactory sockets(boolean withCertificate) {
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (InputStream in = Files.newInputStream(caCert())) {
                trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            KeyManagerFactory kmf = null;
            if (withCertificate) {
                KeyStore keys = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(dir.resolve("client.p12"))) {
                    keys.load(in, STORE_PASSWORD.toCharArray());
                }
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keys, STORE_PASSWORD.toCharArray());
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void generate(Path dir) throws IOException, InterruptedException {
        keytool(dir, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-dname", "CN=valkeyway test CA", "-ext", "bc:c", "-keystore", "ca.p12");
        keytool(dir, "-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-rfc", "-file", "ca.crt");
        issue(dir, "server", "CN=localhost", "san=dns:localhost,ip:127.0.0.1", "serverAuth,clientAuth");
        issue(dir, "client", "CN=valkeyway test client", null, "clientAuth");
    }

    /** Creates {@code name}.p12 with a key pair, has the CA sign it, and writes its PEM files. */
    private static void issue(Path dir, String name, String dname, String san, String eku)
            throws IOException, InterruptedException {
        String store = name + ".p12";
        keytool(dir, "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-dname", dname, "-keystore", store);
        keytool(dir, "-certreq", "-alias", name, "-keystore", store, "-file", name + ".csr");
        List<String> sign = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", "ca.p12",
                "-infile", name + ".csr", "-outfile", name + ".crt", "-rfc", "-validity", "3650",
                "-ext", "ku:c=digitalSignature,keyEncipherment", "-ext", "eku=" + eku));
        if (san != null) {
            sign.addAll(List.of("-ext", san));
        }
        keytool(dir, sign.toArray(String[]::new));
        // The keystore gets the signed chain, so Java clients present it.
        Files.writeString(dir.resolve(name + "-chain.crt"),
                Files.readString(dir.resolve(name + ".crt")) + Files.readString(dir.resolve("ca.crt")));
        keytool(dir, "-importcert", "-alias", name, "-keystore", store, "-file", name + "-chain.crt", "-noprompt");
        writeKey(dir, store, name);
    }

    private static void writeKey(Path dir, String store, String alias) throws IOException {
        try (InputStream in = Files.newInputStream(dir.resolve(store))) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, STORE_PASSWORD.toCharArray());
            PrivateKey key = (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
            Files.writeString(dir.resolve(alias + ".key"), pemKey(key.getEncoded()));
            Certificate[] chain = keyStore.getCertificateChain(alias);
            if (chain.length < 2) {
                throw new IllegalStateException(alias + " was not imported with its CA-signed chain");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String pemKey(byte[] pkcs8) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8)
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static void keytool(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(List.of("-storetype", "PKCS12", "-storepass", STORE_PASSWORD));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("keytool " + String.join(" ", args) + " failed: " + output);
        }
    }
}
