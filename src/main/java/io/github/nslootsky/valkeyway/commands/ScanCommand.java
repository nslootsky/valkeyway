/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import java.util.Arrays;
import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import glide.api.models.commands.scan.ClusterScanCursor;
import glide.api.models.commands.scan.ScanOptions;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.backend.replies.GlideReplies;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SCAN across every node of the cluster: {@code SCAN cursor [MATCH pattern] [COUNT count] [TYPE type]}.
 *
 * <p>Cursors are ids into {@link ScanCursorStore} during an iteration and "0" when it is complete.
 * A cursor belongs to the database it started in. One call returns at least COUNT keys unless the
 * iteration completes (up to {@link #SCAN_MAX_RESULTS}), since Glide's cluster scan returns a few per step.
 *
 * <p>Glide's cluster scan isn't binary-safe: key bytes and MATCH patterns that aren't valid UTF-8 are
 * replaced. [upstream:G12]
 */
public class ScanCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);
    public static final int SCAN_MAX_RESULTS = 5000;
    static final String INVALID_CURSOR = "ERR invalid SCAN cursor";

    private final BackendClients backendClients;
    private final ScanCursorStore scanCursorStore;

    public ScanCommand(BackendClients backendClients, ScanCursorStore scanCursorStore) {
        this.backendClients = backendClients;
        this.scanCursorStore = scanCursorStore;
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    /** Parsed SCAN options, or the error to answer with. */
    record Options(ScanOptions options, long count, String error) {
        static Options error(String error) {
            return new Options(null, 0, error);
        }
    }

    /** Parses the options after the cursor ({@code args[2..]}), with Valkey's errors. */
    static Options parse(byte[][] args) {
        long count = 10;
        byte[] match = null;
        ScanOptions.ObjectType type = null;
        for (int i = 2; i < args.length; i++) {
            String option = new String(args[i], StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            boolean hasValue = i + 1 < args.length;
            if (option.equals("COUNT") && hasValue) {
                try {
                    count = Long.parseLong(new String(args[++i], StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    return Options.error("ERR value is not an integer or out of range");
                }
                if (count < 1) {
                    return Options.error("ERR syntax error");
                }
            } else if (option.equals("MATCH") && hasValue) {
                match = args[++i];
            } else if (option.equals("TYPE") && hasValue) {
                String name = new String(args[++i], StandardCharsets.UTF_8);
                type = objectType(name);
                if (type == null) {
                    return Options.error("ERR unknown type name '" + name + "'");
                }
            } else {
                return Options.error("ERR syntax error");
            }
        }
        var builder = ScanOptions.builder().count(count);
        if (match != null) {
            builder.matchPatternBinary(GlideString.of(match));
        }
        if (type != null) {
            builder.type(type);
        }
        return new Options(builder.build(), count, null);
    }

    private static ScanOptions.ObjectType objectType(String name) {
        for (ScanOptions.ObjectType type : ScanOptions.ObjectType.values()) {
            if (type.getNativeName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'scan' command");
        }
        Connection connection = request.connection();
        Options parsed = parse(request.args());
        if (parsed.error() != null) {
            return Reply.error(parsed.error());
        }

        String cursorArg = request.argString(0);
        ClusterScanCursor cursor;
        if ("0".equals(cursorArg)) {
            cursor = ClusterScanCursor.initialCursor();
        } else {
            ScanCursorStore.Entry entry = scanCursorStore.take(cursorArg);
            if (entry == null) {
                return Reply.error(INVALID_CURSOR);
            }
            if (entry.db() != connection.db()) {
                entry.cursor().releaseCursorHandle();
                return Reply.error(INVALID_CURSOR);
            }
            cursor = entry.cursor();
        }

        try {
            GlideClusterClient client = backendClients.getClient(connection.db());
            List<Object> keys = new ArrayList<>();
            while (!cursor.isFinished() && keys.size() < parsed.count() && keys.size() < SCAN_MAX_RESULTS) {
                Object[] result = client.scanBinary(cursor, parsed.options()).get();
                cursor.releaseCursorHandle();
                cursor = (ClusterScanCursor) result[0];
                if (result[1] != null) {
                    keys.addAll(Arrays.asList((Object[]) result[1]));
                }
            }
            String next;
            if (cursor.isFinished()) {
                cursor.releaseCursorHandle();
                next = "0";
            } else {
                next = scanCursorStore.put(cursor, connection.db());
            }
            log.debug("SCAN keys={} next={}", keys.size(), next);
            return Reply.array(Reply.bulk(next), GlideReplies.toReply(keys.toArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cursor.releaseCursorHandle();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            cursor.releaseCursorHandle();
            log.debug("SCAN ERR {}", e.getMessage());
            return ServerErrors.error(e);
        }
    }
}
