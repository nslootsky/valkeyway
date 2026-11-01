/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import glide.api.models.exceptions.RequestException;
import io.github.nslootsky.valkeyway.backend.Backend;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

/**
 * Runs a command that can block (BLPOP, XREAD BLOCK, WAIT, ...) on a leased, dedicated client, so it
 * never blocks the shared multiplexed connection every other proxy client uses. Time spent waiting
 * for a free client counts against the command's own timeout. [upstream:V5]
 */
public final class BlockingCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(BlockingCommandRunner.class);

    private final BlockingClientPool pool;
    private final Backend backend;

    public BlockingCommandRunner(BlockingClientPool pool, Backend backend) {
        this.pool = pool;
        this.backend = backend;
    }

    /** Runs {@code args}, a blocking command described by {@code spec}, in {@code db}. Blocks the calling (virtual) thread. */
    public Reply run(int db, byte[][] args, BlockingCommands.Spec spec) throws InterruptedException {
        long start = System.nanoTime();
        var lease = pool.acquire(db, spec.timeoutMillis());
        if (lease == null) {
            log.debug("BLOCKING {} timed out waiting for a pooled client", name(args));
            return timeoutReply(spec.timeoutReply());
        }
        try {
            long waitedMillis = (System.nanoTime() - start) / 1_000_000;
            byte[][] effective = spec.withRemainingTimeout(args, waitedMillis);
            return Backend.toReply(args, backend.send(lease.resource(), effective).get());
        } catch (ExecutionException e) {
            // A server error leaves the connection usable. Anything else (timeout, connection loss)
            // may leave the blocking command pending on it, so don't hand it to the next caller.
            if (!(e.getCause() instanceof RequestException)) {
                lease.discard();
            }
            log.debug("ERR {} error={}", name(args), e.getMessage());
            return ServerErrors.error(e);
        } catch (InterruptedException e) {
            lease.discard();
            throw e;
        } finally {
            lease.close();
        }
    }

    private static Reply timeoutReply(BlockingCommands.TimeoutReply reply) {
        return switch (reply) {
            case NULL -> Reply.Null.ARRAY;
            case ZERO -> Reply.ZERO;
            case ZERO_PAIR -> Reply.array(Reply.ZERO, Reply.ZERO);
        };
    }

    private static String name(byte[][] args) {
        return new String(args[0], StandardCharsets.US_ASCII);
    }
}
