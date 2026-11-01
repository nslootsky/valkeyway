/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;

/**
 * Executes one command. Runs on the connection's virtual thread, so it may block (e.g. on a Glide
 * future) without holding up the network threads or other connections.
 */
@FunctionalInterface
public interface CommandHandler {

    Reply execute(Request request) throws Exception;
}
