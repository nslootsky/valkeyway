/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
import glide.api.models.commands.scan.ClusterScanCursor;
import glide.api.models.commands.scan.ScanOptions;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.GlideReplies;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
import io.github.nslootsky.valkeyway.scan.ScanCursorStore;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SCAN: iterates across all cluster nodes. Cursors are UUIDs during iteration, "0" on completion.
 */
public class ScanCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);
    public static final int SCAN_MAX_RESULTS = 5000;

    private final GlideClientCache glideClientCache;
    private final ScanCursorStore scanCursorStore;

    public ScanCommand(GlideClientCache glideClientCache, ScanCursorStore scanCursorStore) {
        this.glideClientCache = glideClientCache;
        this.scanCursorStore = scanCursorStore;
    }

    @Override
    public Reply execute(Request request) {
        Connection connection = request.connection();
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'scan' command");
        }

        String cursorArg = request.argString(0);
        String matchPattern = null;
        long count = 10;

        for (int i = 1; i < request.argCount(); i++) {
            String opt = request.argString(i).toUpperCase();
            if ("MATCH".equals(opt) && i + 1 < request.argCount()) {
                matchPattern = request.argString(++i);
            } else if ("COUNT".equals(opt) && i + 1 < request.argCount()) {
                try {
                    count = Long.parseLong(request.argString(++i));
                } catch (NumberFormatException e) {
                    // ignore invalid count
                }
            }
        }

        ClusterScanCursor scanCursor;
        if ("0".equals(cursorArg)) {
            scanCursor = ClusterScanCursor.initialCursor();
            String currentCursorId = connection.scanCursorId();
            if (currentCursorId != null) {
                scanCursorStore.removeCursor(currentCursorId);
                connection.scanCursorId(null);
            }
        } else {
            scanCursor = scanCursorStore.getCursor(cursorArg);
            if (scanCursor == null) {
                return Reply.error("ERR invalid SCAN cursor");
            }
        }

        ScanOptions.ScanOptionsBuilder<?, ?> optionsBuilder = ScanOptions.builder().count(count);
        if (matchPattern != null) {
            optionsBuilder.matchPattern(matchPattern);
        }
        ScanOptions options = optionsBuilder.build();

        log.debug("SCAN cursor={} match={} count={}", cursorArg, matchPattern, count);

        try {
            GlideClusterClient client = glideClientCache.getClient(connection.db());
            List<Object> allKeys = new ArrayList<>();
            ClusterScanCursor currentCursor = scanCursor;

            while (!currentCursor.isFinished() && allKeys.size() < count && allKeys.size() < SCAN_MAX_RESULTS) {
                Object[] result = client.scan(currentCursor, options).get();
                ClusterScanCursor nextCursor = (ClusterScanCursor) result[0];
                Object[] keys = (Object[]) result[1];

                currentCursor.releaseCursorHandle();
                currentCursor = nextCursor;

                if (keys != null) {
                    Collections.addAll(allKeys, keys);
                }
            }

            String nextCursorStr;
            if (currentCursor.isFinished()) {
                nextCursorStr = "0";
                currentCursor.releaseCursorHandle();
                String cursorId = connection.scanCursorId();
                if (cursorId != null) {
                    scanCursorStore.removeCursor(cursorId);
                    connection.scanCursorId(null);
                }
            } else {
                nextCursorStr = scanCursorStore.createCursor(currentCursor);
                connection.scanCursorId(nextCursorStr);
            }

            log.debug("SCAN OK keys={} finished={} cursor={}", allKeys.size(), currentCursor.isFinished(), nextCursorStr);
            return Reply.array(Reply.bulk(nextCursorStr), GlideReplies.toReply(allKeys.toArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            log.debug("SCAN ERR {}", e.getMessage());
            return ServerErrors.error(e);
        }
    }
}
