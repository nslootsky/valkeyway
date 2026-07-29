/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.annotation.ParamLength;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.commands.scan.ClusterScanCursor;
import glide.api.models.commands.scan.ScanOptions;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import io.github.nslootsky.valkeyway.scan.ScanCursorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SCAN command handler. Iterates across all cluster nodes.
 * Cursors are UUIDs during iteration, "0" on completion.
 */
@Command("scan")
@ParamLength(value = 1, option = 10)
public class ScanCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);

    private final GlideClientCache glideClientCache;
    private final ScanCursorStore scanCursorStore;

    public ScanCommand(GlideClientCache glideClientCache, ScanCursorStore scanCursorStore) {
        this.glideClientCache = glideClientCache;
        this.scanCursorStore = scanCursorStore;
    }

    @Override
    public RedisToken execute(Request request) {
        var session = request.getSession();
        if (request.getLength() < 1) {
            return RedisToken.error("ERR wrong number of arguments for 'scan' command");
        }

        String cursorArg = request.getParam(0).toString();
        String matchPattern = null;
        long count = 10;

        for (int i = 1; i < request.getLength(); i++) {
            String opt = request.getParam(i).toString().toUpperCase();
            if ("MATCH".equals(opt) && i + 1 < request.getLength()) {
                matchPattern = request.getParam(++i).toString();
            } else if ("COUNT".equals(opt) && i + 1 < request.getLength()) {
                try {
                    count = Long.parseLong(request.getParam(++i).toString());
                } catch (NumberFormatException e) {
                    // ignore invalid count
                }
            }
        }

        ClusterScanCursor scanCursor;
        if ("0".equals(cursorArg)) {
            scanCursor = ClusterScanCursor.initialCursor();
            String currentCursorId = SessionState.getScanCursorId(session);
            if (currentCursorId != null) {
                scanCursorStore.removeCursor(currentCursorId);
                SessionState.clearScanCursorId(session);
            }
        } else {
            scanCursor = scanCursorStore.getCursor(cursorArg);
            if (scanCursor == null) {
                return RedisToken.error("ERR invalid SCAN cursor");
            }
        }

        ScanOptions.ScanOptionsBuilder<?, ?> optionsBuilder =
                ScanOptions.builder().count(count);
        if (matchPattern != null) {
            optionsBuilder.matchPattern(matchPattern);
        }
        ScanOptions options =
                optionsBuilder.build();

        log.debug("SCAN cursor={} match={} count={}", cursorArg, matchPattern, count);

        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(session, glideClientCache);
            List<Object> allKeys = new ArrayList<>();
            ClusterScanCursor currentCursor = scanCursor;

            while (!currentCursor.isFinished() && allKeys.size() < count) {
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
                String cursorId = SessionState.getScanCursorId(session);
                if (cursorId != null) {
                    scanCursorStore.removeCursor(cursorId);
                    SessionState.clearScanCursorId(session);
                }
            } else {
                nextCursorStr = scanCursorStore.createCursor(currentCursor);
                SessionState.setScanCursorId(session, nextCursorStr);
            }

            log.debug("SCAN OK keys={} finished={} cursor={}", allKeys.size(), currentCursor.isFinished(), nextCursorStr);
            return RedisToken.array(
                    RedisToken.string(nextCursorStr),
                    TokenUtils.toRedisArray(allKeys.toArray())
            );
        } catch (Exception e) {
            log.error("SCAN ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
