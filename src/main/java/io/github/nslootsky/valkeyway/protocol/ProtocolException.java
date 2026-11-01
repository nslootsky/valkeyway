/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

/**
 * A malformed request. Like Valkey, the server replies {@code -ERR Protocol error: <detail>} and
 * closes the connection, because the stream can't be resynchronised.
 */
public final class ProtocolException extends RuntimeException {

    public ProtocolException(String detail) {
        super(detail, null, false, false);
    }

    /** The error reply text, in Valkey's wording. */
    public String reply() {
        return "ERR Protocol error: " + getMessage();
    }
}
