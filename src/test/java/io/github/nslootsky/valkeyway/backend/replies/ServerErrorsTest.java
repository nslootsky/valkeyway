/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.replies;

import io.github.nslootsky.valkeyway.protocol.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerErrorsTest {

    /**
     * Raw Glide messages captured from a Valkey 9.1 cluster, paired with what the server itself sends.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - ResponseError: wrong number of arguments for 'get' command"
                    + " | ERR wrong number of arguments for 'get' command",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - NoScriptError: No matching script."
                    + " | NOSCRIPT No matching script.",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - CrossSlot: Keys in request don't hash to the same slot"
                    + " | CROSSSLOT Keys in request don't hash to the same slot",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - PermissionDenied: User proxy has no permissions to run the 'shutdown' command"
                    + " | NOPERM User proxy has no permissions to run the 'shutdown' command",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - NotBusy: No scripts in execution right now."
                    + " | NOTBUSY No scripts in execution right now.",
            "glide.api.models.exceptions.RequestException: WRONGTYPE: Operation against a key holding the wrong kind of value"
                    + " | WRONGTYPE Operation against a key holding the wrong kind of value",
            "glide.api.models.exceptions.RequestException: BUSYGROUP: Consumer Group name already exists"
                    + " | BUSYGROUP Consumer Group name already exists",
            "An error was signalled by the server: - ExecAbortError: Transaction discarded because of previous errors."
                    + " | EXECABORT Transaction discarded because of previous errors.",
            "ResponseError: syntax error | ERR syntax error",
            "ERR invalid SCAN cursor | ERR invalid SCAN cursor",
    })
    void errorMessagesUseServerErrorCodes(String glideMessage, String expected) {
        assertEquals(expected, ServerErrors.message(glideMessage));
    }

    @Test
    void nullErrorMessageStillHasCode() {
        assertEquals("ERR Unknown error", ServerErrors.message(null));
    }

    /** What clients saw while a primary was killed (TopologyChangeTest), before the rewrite. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "MOVED 3739 127.0.0.1:27103; ExtensionError"
                    + " | ERR backend unavailable: can't reach 127.0.0.1:27103 (slot 3739); the command did not run",
            "ASK 3739 127.0.0.1:27103"
                    + " | ERR backend unavailable: can't reach 127.0.0.1:27103 (slot 3739); the command did not run",
            "ERR Received connection error `Requested connection not found - ConnectionNotFoundForRoute: 127.0.0.1:27103`. Will attempt to reconnect"
                    + " | ERR backend unavailable: no connection to 127.0.0.1:27103; the command did not run",
            "ERR BrokenPipe: Cluster: Failed to receive command response from internal sender. RecvError(())"
                    + " | ERR backend unavailable: lost the connection to a cluster node; the command may or may not have run",
            "ERR Received connection error `Cluster: Failed to receive command response from internal sender. RecvError(())`. Will attempt to reconnect"
                    + " | ERR backend unavailable: lost the connection to a cluster node; the command may or may not have run",
            "ERR timed out | ERR backend request timed out; the command may or may not have run",
            "TRYAGAIN Multiple keys request during rehashing of slot | TRYAGAIN Multiple keys request during rehashing of slot",
    })
    void backendFailuresAreRewritten(String message, String expected) {
        assertEquals(expected, ServerErrors.message(message));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "TRYAGAIN Multiple keys request during rehashing of slot | true",
            "CLUSTERDOWN The cluster is down | true",
            "MASTERDOWN Link with MASTER is down | true",
            "LOADING Valkey is loading the dataset in memory | true",
            "READONLY You can't write against a read only replica. | true",
            "MOVED 3999 127.0.0.1:6381 | true",
            "ASK 3999 127.0.0.1:6381 | true",
            "ERR Request timed out | false",
            "WRONGTYPE Operation against a key holding the wrong kind of value | false",
            "ERR BrokenPipe: Cluster: Failed to receive command response | false",
            "TRYAGAINX | false",
            "ERR backend unavailable: no connection to 127.0.0.1:7003; the command did not run | true",
            "ERR backend unavailable: lost the connection to a cluster node; the command may or may not have run | false",
    })
    void recognisesErrorsRejectedWithoutRunning(String message, boolean expected) {
        assertEquals(expected, ServerErrors.rejectedWithoutRunning(new Reply.Error(message)));
    }

    @Test
    void glidesTypedFailuresAreRecognisedByType() {
        assertEquals(new Reply.Error("ERR backend request timed out; the command may or may not have run"),
                ServerErrors.error(new java.util.concurrent.ExecutionException(
                        new glide.api.models.exceptions.TimeoutException("Request timed out"))));
        assertEquals(new Reply.Error("ERR backend unavailable: the backend client is closed; the command may or may not have run"),
                ServerErrors.error(new java.util.concurrent.CompletionException(
                        new glide.api.models.exceptions.ClosingException("Client closed: Unable to submit command."))));
    }

    @Test
    void recognisesRedirectsGlideDidNotFollow() {
        assertEquals(true, ServerErrors.isUnfollowedRedirect(new java.util.concurrent.CompletionException(
                new glide.api.models.exceptions.RequestException(
                        "An error was signalled by the server: - Moved: 11420 127.0.0.1:27102"))));
        assertEquals(true, ServerErrors.isUnfollowedRedirect(new RuntimeException("MOVED 3739 127.0.0.1:27103; ExtensionError")));
        assertEquals(false, ServerErrors.isUnfollowedRedirect(new RuntimeException("TRYAGAIN Multiple keys request")));
        assertEquals(false, ServerErrors.isUnfollowedRedirect(new RuntimeException((String) null)));
    }
}
