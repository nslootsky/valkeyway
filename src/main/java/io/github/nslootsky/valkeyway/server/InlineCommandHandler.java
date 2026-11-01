/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

/**
 * A handler cheap and side-effect free enough to run directly on the network thread (PING, ECHO,
 * TIME, ...): no I/O, no blocking, no change to connection state.
 */
public interface InlineCommandHandler extends CommandHandler {
}
