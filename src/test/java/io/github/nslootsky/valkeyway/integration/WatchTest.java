/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WATCH state belongs to a connection. Each proxy client must get the semantics of its own
 * connection, unaffected by other clients' WATCH, UNWATCH and EXEC.
 */
class WatchTest extends IntegrationTestBase {

    private static final Status OK = new Status("OK");

    private static RawRespClient connect() throws Exception {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    @Test
    void aWatchedKeyChangedByAnotherClientAbortsExec() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "{w1}k", "1");
            assertEquals(OK, a.call("WATCH", "{w1}k"));
            b.call("SET", "{w1}k", "changed");
            a.call("MULTI");
            a.call("SET", "{w1}k", "fromA");
            assertEquals(RawRespClient.NULL_ARRAY, a.call("EXEC"));
            assertEquals("changed", a.call("GET", "{w1}k"));
        }
    }

    @Test
    void anotherClientsExecDoesNotClearMyWatch() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect(); RawRespClient c = connect()) {
            a.call("SET", "{w2}k", "1");
            assertEquals(OK, a.call("WATCH", "{w2}k"));
            // B runs a transaction on the same slot (same node).
            b.call("MULTI");
            b.call("SET", "{w2}other", "x");
            assertEquals(List.of(OK), b.call("EXEC"));
            c.call("SET", "{w2}k", "changed");
            a.call("MULTI");
            a.call("SET", "{w2}k", "fromA");
            assertEquals(RawRespClient.NULL_ARRAY, a.call("EXEC"), "A's watch must survive B's EXEC");
            assertEquals("changed", a.call("GET", "{w2}k"));
        }
    }

    @Test
    void anotherClientsUnwatchDoesNotClearMyWatch() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "{w3}k", "1");
            assertEquals(OK, a.call("WATCH", "{w3}k"));
            assertEquals(OK, b.call("UNWATCH"));
            b.call("SET", "{w3}k", "changed");
            a.call("MULTI");
            a.call("SET", "{w3}k", "fromA");
            assertEquals(RawRespClient.NULL_ARRAY, a.call("EXEC"), "A's watch must survive B's UNWATCH");
        }
    }

    @Test
    void anotherClientsWatchDoesNotAbortMyExec() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "{w4}k", "1");
            assertEquals(OK, a.call("WATCH", "{w4}k"));
            a.call("SET", "{w4}k", "changedByA");
            // B never watched anything; its transaction must run.
            b.call("MULTI");
            b.call("SET", "{w4}other", "x");
            assertEquals(List.of(OK), b.call("EXEC"), "B's EXEC must not be aborted by A's watch");
            a.call("UNWATCH");
        }
    }

    @Test
    void execUnwatchesAndUnwatchWorks() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "{w5}k", "1");
            a.call("WATCH", "{w5}k");
            a.call("MULTI");
            a.call("SET", "{w5}k", "2");
            assertEquals(List.of(OK), a.call("EXEC"));
            // EXEC dropped the watch, so a later change doesn't abort the next transaction.
            b.call("SET", "{w5}k", "changed");
            a.call("MULTI");
            a.call("SET", "{w5}k", "3");
            assertEquals(List.of(OK), a.call("EXEC"));

            a.call("WATCH", "{w5}k");
            assertEquals(OK, a.call("UNWATCH"));
            b.call("SET", "{w5}k", "again");
            a.call("MULTI");
            a.call("SET", "{w5}k", "4");
            assertEquals(List.of(OK), a.call("EXEC"));
        }
    }

    /** As in Valkey 9: an error that also discards the transaction (also covered by TransactionFidelityTest). */
    @Test
    void watchInsideMultiIsAnError() throws Exception {
        try (RawRespClient a = connect()) {
            a.call("MULTI");
            Object reply = a.call("WATCH", "{w6}k");
            assertEquals(new RawRespClient.Error("ERR Command 'watch' not allowed inside a transaction"), reply);
            a.call("SET", "{w6}k", "v");
            assertEquals(new RawRespClient.Error("EXECABORT Transaction discarded because of previous errors."), a.call("EXEC"));
        }
    }

    @Test
    void closingAConnectionReleasesItsWatch() throws Exception {
        try (RawRespClient a = connect()) {
            a.call("SET", "{w7}k", "1");
            a.call("WATCH", "{w7}k");
        }
        // Whatever backend connection A's watch used must not carry it over to someone else.
        try (RawRespClient b = connect(); RawRespClient c = connect()) {
            c.call("SET", "{w7}k", "changed");
            b.call("MULTI");
            b.call("SET", "{w7}other", "x");
            assertEquals(List.of(OK), b.call("EXEC"));
        }
    }

    @Test
    void unwatchInsideMultiIsQueued() throws Exception {
        try (RawRespClient a = connect()) {
            a.call("WATCH", "{w8}k");
            a.call("MULTI");
            assertEquals(new Status("QUEUED"), a.call("UNWATCH"));
            a.call("SET", "{w8}k", "v");
            assertEquals(List.of(OK, OK), a.call("EXEC"));
        }
    }

    @Test
    void anEmptyTransactionStillChecksWatchedKeys() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "{w9}k", "1");
            a.call("WATCH", "{w9}k");
            a.call("MULTI");
            assertEquals(List.of(), a.call("EXEC"), "unchanged: an empty result");

            a.call("WATCH", "{w9}k");
            b.call("SET", "{w9}k", "changed");
            a.call("MULTI");
            assertEquals(RawRespClient.NULL_ARRAY, a.call("EXEC"), "changed: aborted");
        }
    }

    /** Watches in one database can't be checked by an EXEC in another; the proxy aborts rather than run unprotected. */
    @Test
    void selectingAnotherDatabaseAfterWatchAbortsExec() throws Exception {
        try (RawRespClient a = connect()) {
            a.call("WATCH", "{w10}k");
            a.call("SELECT", "1");
            a.call("MULTI");
            a.call("SET", "{w10}k", "v");
            assertEquals(RawRespClient.NULL_ARRAY, a.call("EXEC"));
            a.call("SELECT", "0");
        }
    }

    @Test
    void watchingKeysInSeveralSlotsRejectsExec() throws Exception {
        try (RawRespClient a = connect()) {
            assertEquals(OK, a.call("WATCH", "w11:a", "w11:b"));
            a.call("MULTI");
            a.call("SET", "w11:a", "v");
            Object reply = a.call("EXEC");
            assertTrue(reply instanceof RawRespClient.Error e && e.message().startsWith("CROSSSLOT "), "got " + reply);
        }
    }

    @Test
    void discardDropsTheWatch() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "{w12}k", "1");
            a.call("WATCH", "{w12}k");
            a.call("MULTI");
            assertEquals(OK, a.call("DISCARD"));
            b.call("SET", "{w12}k", "changed");
            a.call("MULTI");
            a.call("SET", "{w12}k", "v");
            assertEquals(List.of(OK), a.call("EXEC"));
        }
    }
}
