/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import glide.api.models.Batch;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void multiExec() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.multi());
            assertEquals("QUEUED", client.set("{tx}key1", "val1"));
            assertEquals("QUEUED", client.set("{tx}key2", "val2"));
            Object[] result = client.exec();
            assertNotNull(result);
            assertEquals(2, result.length);
            assertEquals("OK", result[0]);
            assertEquals("OK", result[1]);
            assertEquals("val1", client.get("{tx}key1"));
            assertEquals("val2", client.get("{tx}key2"));
        }
    }

    @Test
    @Order(2)
    void discard() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.multi());
            assertEquals("QUEUED", client.set("discard_key", "should_not_exist"));
            assertEquals("OK", client.discard());
            assertNull(client.get("discard_key"));
        }
    }

    @Test
    @Order(3)
    void glideBatchTransaction() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            Batch batch = new Batch(true);
            batch.set("{batch}key1", "batch_val1");
            batch.set("{batch}key2", "batch_val2");
            batch.get("{batch}key1");
            Object[] result = client.getClient().exec(batch, true).get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(3, result.length);
            assertEquals("OK", result[0]);
            assertEquals("OK", result[1]);
            assertEquals("batch_val1", result[2]);
        }
    }

    @Test
    @Order(4)
    void glideBatchPipeline() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            Batch pipeline = new Batch(false);
            pipeline.set("pipe_key1", "pipe_val1");
            pipeline.set("pipe_key2", "pipe_val2");
            pipeline.get("pipe_key1");
            Object[] result = client.getClient().exec(pipeline, false).get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(3, result.length);
            assertEquals("OK", result[0]);
            assertEquals("OK", result[1]);
            assertEquals("pipe_val1", result[2]);
        }
    }

    /** A standalone client's atomic batch (MULTI/EXEC) across slots: rejected, as a cluster node would. */
    @Test
    @Order(5)
    void glideAtomicBatchAcrossSlotsIsRejected() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            Batch batch = new Batch(true);
            batch.set("txa:one", "1");
            batch.set("txa:two", "2");
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.getClient().exec(batch, true).get(5, TimeUnit.SECONDS));
            // Glide's client reports the proxy's CROSSSLOT error as "CrossSlot: ...".
            assertTrue(e.getCause().getMessage().toUpperCase().contains("CROSSSLOT"), e.getCause().getMessage());
            assertNull(client.get("txa:one"));
        }
    }

    /** A Glide atomic batch with a command the proxy normally answers itself still lines up with its results. */
    @Test
    @Order(6)
    void glideAtomicBatchWithPing() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            Batch batch = new Batch(true);
            batch.set("{txp}a", "1");
            batch.ping();
            batch.get("{txp}a");
            Object[] result = client.getClient().exec(batch, true).get(5, TimeUnit.SECONDS);
            assertArrayEquals(new Object[]{"OK", "PONG", "1"}, result);
        }
    }
}
