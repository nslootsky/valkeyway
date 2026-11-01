/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UsersTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void withoutPasswordsTheDefaultUserAcceptsAnything() {
        assertFalse(Users.NONE.authRequired());
        assertTrue(Users.NONE.check("default", b("whatever")));
        assertFalse(Users.NONE.check("bob", b("whatever")));
    }

    @Test
    void aDefaultPasswordRequiresAuth() {
        Users users = new Users("secret", Map.of());
        assertTrue(users.authRequired());
        assertTrue(users.check("default", b("secret")));
        assertFalse(users.check("default", b("Secret")));
        assertFalse(users.check("default", b("")));
    }

    @Test
    void namedUsersHaveTheirOwnPasswords() {
        Users users = new Users("secret", Map.of("app", "apppw", "ops", "opspw"));
        assertTrue(users.check("app", b("apppw")));
        assertFalse(users.check("app", b("secret")));
        assertFalse(users.check("ops", b("apppw")));
        assertFalse(users.check("nobody", b("apppw")));
        assertEquals(Set.of("default", "app", "ops"), users.names());
    }

    @Test
    void namedUsersAloneLeaveTheDefaultUserPasswordless() {
        Users users = new Users(null, Map.of("app", "apppw"));
        assertFalse(users.authRequired());
        assertTrue(users.check("app", b("apppw")));
        assertFalse(users.check("app", b("x")));
    }

    @Test
    void aUsersEntryForDefaultSetsItsPassword() {
        Users users = new Users(null, Map.of("default", "pw"));
        assertTrue(users.authRequired());
        assertTrue(users.check("default", b("pw")));
    }

    @Test
    void passwordsAreBinarySafe() {
        Users users = new Users("pässwörd", Map.of());
        assertTrue(users.check("default", b("pässwörd")));
    }

    @Test
    void aUserWithoutAPasswordIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class, () -> new Users(null, Map.of("app", "")));
    }
}
