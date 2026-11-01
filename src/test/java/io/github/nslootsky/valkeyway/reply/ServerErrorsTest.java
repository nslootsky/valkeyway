/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.reply;

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
            "glide.api.models.exceptions.TimeoutException: Request timed out | ERR Request timed out",
    })
    void errorMessagesUseServerErrorCodes(String glideMessage, String expected) {
        assertEquals(expected, ServerErrors.message(glideMessage));
    }

    @Test
    void nullErrorMessageStillHasCode() {
        assertEquals("ERR Unknown error", ServerErrors.message(null));
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
    })
    void recognisesErrorsRejectedWithoutRunning(String message, boolean expected) {
        assertEquals(expected, ServerErrors.rejectedWithoutRunning(new Reply.Error(message)));
    }
}
