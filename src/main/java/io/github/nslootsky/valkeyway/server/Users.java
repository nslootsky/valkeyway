/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The proxy's own users, for client AUTH. Every user has full access; there are no ACL rules.
 *
 * <p>The {@code default} user is the one AUTH with only a password authenticates. Without a password
 * it is passwordless, as on a Valkey server without {@code requirepass}: every connection starts
 * authenticated as it. With a password, connections start unauthenticated and must AUTH first.
 *
 * <p>Passwords are kept as SHA-256 digests and compared in constant time.
 */
public final class Users {

    public static final String DEFAULT = "default";

    /** No passwords: every connection is the passwordless default user. */
    public static final Users NONE = new Users(null, Map.of());

    private final byte[] defaultPassword;
    private final Map<String, byte[]> passwords = new HashMap<>();

    /**
     * @param defaultPassword the default user's password, or {@code null} for none
     * @param users           other users and their passwords; an entry for {@code default} sets its password
     */
    public Users(String defaultPassword, Map<String, String> users) {
        if (users.containsKey(DEFAULT) && defaultPassword != null && !defaultPassword.isEmpty()) {
            throw new IllegalArgumentException(
                    "Set the default user's password with proxy.auth.password or proxy.auth.users.default, not both");
        }
        String effectiveDefault = users.getOrDefault(DEFAULT, defaultPassword);
        this.defaultPassword = effectiveDefault == null || effectiveDefault.isEmpty() ? null : digest(effectiveDefault);
        users.forEach((name, password) -> {
            if (!name.equals(DEFAULT)) {
                if (password == null || password.isEmpty()) {
                    throw new IllegalArgumentException("proxy.auth.users." + name + " has no password");
                }
                passwords.put(name, digest(password));
            }
        });
    }

    /** Whether connections must authenticate before running commands. */
    public boolean authRequired() {
        return defaultPassword != null;
    }

    /** Whether {@code user} exists and {@code password} is its password. */
    public boolean check(String user, byte[] password) {
        if (user.equals(DEFAULT)) {
            // A passwordless default user accepts any password, as on a server.
            return defaultPassword == null || MessageDigest.isEqual(defaultPassword, digest(password));
        }
        byte[] expected = passwords.get(user);
        // Hash anyway, so an unknown user takes as long as a wrong password.
        byte[] given = digest(password);
        return expected != null && MessageDigest.isEqual(expected, given);
    }

    /** The configured user names (the default user always exists). */
    public Set<String> names() {
        Set<String> names = new TreeSet<>(passwords.keySet());
        names.add(DEFAULT);
        return names;
    }

    private static byte[] digest(String password) {
        return digest(password.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] password) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(password);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
