/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import java.nio.charset.StandardCharsets;

/**
 * One decoded command: its name and arguments as raw bytes, plus the connection it came from.
 * Argument indexes passed to {@link #arg(int)} exclude the command name.
 */
public final class Request {

    private final Connection connection;
    private final byte[][] args;

    public Request(Connection connection, byte[][] args) {
        this.connection = connection;
        this.args = args;
    }

    public Connection connection() {
        return connection;
    }

    /** Command name followed by its arguments, exactly as received. */
    public byte[][] args() {
        return args;
    }

    /** The command name as sent by the client. */
    public String command() {
        return new String(args[0], StandardCharsets.US_ASCII);
    }

    /** Number of arguments after the command name. */
    public int argCount() {
        return args.length - 1;
    }

    /** Argument {@code i} (0 = first after the command name). */
    public byte[] arg(int i) {
        return args[i + 1];
    }

    /** Argument {@code i} decoded as UTF-8. */
    public String argString(int i) {
        return new String(args[i + 1], StandardCharsets.UTF_8);
    }
}
