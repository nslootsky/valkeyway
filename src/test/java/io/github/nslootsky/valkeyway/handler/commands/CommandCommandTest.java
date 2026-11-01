/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.protocol.Reply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * COMMAND DOCS flag values must be status replies (redis-cli asserts on it); everything else in
 * the docs stays a bulk string.
 */
class CommandCommandTest {

    private final CommandCommand command = new CommandCommand(null);

    @Test
    void docsFlagValuesBecomeStatusReplies() {
        Object[] flags = {GlideString.of("optional"), GlideString.of("multiple"), GlideString.of("multiple_token")};
        assertEquals(Reply.array(new Reply.Simple("optional"), new Reply.Simple("multiple"), new Reply.Simple("multiple_token")),
                command.toReplyForDocs(flags));
    }

    @Test
    void docsNonFlagValuesRemainBulkStrings() {
        Object[] args = {GlideString.of("hello"), GlideString.of("world")};
        assertEquals(Reply.array(Reply.bulk("hello"), Reply.bulk("world")), command.toReplyForDocs(args));
    }

    @Test
    void docsMixedValuesHandledCorrectly() {
        Object[] mixed = {GlideString.of("optional"), GlideString.of("regular_value"), 3L};
        assertEquals(Reply.array(new Reply.Simple("optional"), Reply.bulk("regular_value"), Reply.integer(3)),
                command.toReplyForDocs(mixed));
    }
}
