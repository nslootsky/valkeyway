/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.reply;

import io.github.nslootsky.valkeyway.protocol.Reply;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Glide/Java exceptions into error replies in the server's format, "CODE detail", so
 * clients can dispatch on the code (WRONGTYPE, NOSCRIPT, CROSSSLOT, ...).
 */
public final class ServerErrors {

    private ServerErrors() {}

    /** Java exception class prefix, e.g. "glide.api.models.exceptions.RequestException: ". */
    private static final Pattern EXCEPTION_PREFIX =
            Pattern.compile("^(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*: ");

    /** Glide's wrapper around server errors it recognises: "<description>- <Kind>: <detail>". */
    private static final Pattern GLIDE_KIND_ERROR = Pattern.compile(
            "^(?:An error was signalled by the server|Received crossed slots in pipeline):? ?- ?([A-Za-z]+): (.*)$",
            Pattern.DOTALL);

    /** A bare "<Kind>: <detail>" as produced for some Glide error kinds. */
    private static final Pattern BARE_KIND_ERROR = Pattern.compile("^([A-Z][a-z][A-Za-z]*): (.*)$", Pattern.DOTALL);

    /** Server error codes Glide passes through as "CODE: detail" (WRONGTYPE, BUSYGROUP, NOGROUP, ...). */
    private static final Pattern CODE_COLON_ERROR = Pattern.compile("^([A-Z][A-Z0-9_]+): (.*)$", Pattern.DOTALL);

    /** Already in wire format: "CODE detail". */
    private static final Pattern CODE_SPACE_ERROR = Pattern.compile("^[A-Z][A-Z0-9_]{2,} .*", Pattern.DOTALL);

    /** Glide (redis-rs) error kind names mapped back to the server's error codes. */
    private static final Map<String, String> GLIDE_KIND_CODES = Map.ofEntries(
            Map.entry("ResponseError", "ERR"),
            Map.entry("ExecAbortError", "EXECABORT"),
            Map.entry("BusyLoadingError", "LOADING"),
            Map.entry("NoScriptError", "NOSCRIPT"),
            Map.entry("Moved", "MOVED"),
            Map.entry("Ask", "ASK"),
            Map.entry("TryAgain", "TRYAGAIN"),
            Map.entry("ClusterDown", "CLUSTERDOWN"),
            Map.entry("CrossSlot", "CROSSSLOT"),
            Map.entry("MasterDown", "MASTERDOWN"),
            Map.entry("ReadOnly", "READONLY"),
            Map.entry("NotBusy", "NOTBUSY"));

    /**
     * Error codes with which a cluster node rejects a command without running it, and which Glide
     * retries for a single command (waiting, refreshing slots or following the redirect). Inside a
     * batch Glide returns them, except MOVED/ASK when the redirect target is reachable.
     */
    private static final java.util.Set<String> REJECTED_UNRUN =
            java.util.Set.of("TRYAGAIN", "CLUSTERDOWN", "MASTERDOWN", "LOADING", "READONLY", "MOVED", "ASK");

    /**
     * Whether {@code error} means the node rejected the command without running it, for a transient
     * cluster reason, so sending it again is safe and may succeed.
     */
    public static boolean rejectedWithoutRunning(Reply.Error error) {
        String message = error.message();
        int space = message.indexOf(' ');
        return REJECTED_UNRUN.contains(space < 0 ? message : message.substring(0, space))
                || (message.startsWith(BACKEND_UNAVAILABLE) && message.endsWith(DID_NOT_RUN));
    }

    private static final String BACKEND_UNAVAILABLE = "ERR backend unavailable: ";
    private static final String DID_NOT_RUN = "; the command did not run";

    /** An error reply for {@code e}, unwrapping {@link ExecutionException}. */
    public static Reply error(Throwable e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return new Reply.Error(message(cause.getMessage()));
    }

    /** Converts a Glide/Java exception message to "CODE detail". */
    public static String message(String msg) {
        return backendFailure(serverFormat(msg));
    }

    /** A redirect Glide returned because it couldn't follow it: "MOVED 3739 127.0.0.1:7003; ExtensionError". */
    private static final Pattern UNFOLLOWED_REDIRECT = Pattern.compile("^(?:MOVED|ASK) (\\d+) (\\S+?);?(?: .*)?$");

    /** Glide's "no connection to this node (yet)", e.g. "... ConnectionNotFoundForRoute: 127.0.0.1:7003`...". */
    private static final Pattern NO_CONNECTION = Pattern.compile("ConnectionNotFoundForRoute: ([^`\\s]+)");

    /**
     * Rewrites Glide's messages for an unreachable or lost node, which mean nothing to a client of a
     * standalone server (a raw MOVED even makes Jedis throw JedisMovedDataException), as
     * "ERR backend unavailable: ...", saying whether the command may have run.
     */
    private static String backendFailure(String m) {
        Matcher redirect = UNFOLLOWED_REDIRECT.matcher(m);
        if (redirect.matches()) {
            return BACKEND_UNAVAILABLE + "can't reach " + redirect.group(2) + " (slot " + redirect.group(1) + ")"
                    + DID_NOT_RUN;
        }
        if (!m.startsWith("ERR ")) {
            return m;
        }
        Matcher noConnection = NO_CONNECTION.matcher(m);
        if (noConnection.find()) {
            return BACKEND_UNAVAILABLE + "no connection to " + noConnection.group(1) + DID_NOT_RUN;
        }
        if (m.contains("BrokenPipe") || m.contains("Failed to receive command response")
                || m.startsWith("ERR Received connection error")) {
            return BACKEND_UNAVAILABLE + "lost the connection to a cluster node; the command may or may not have run";
        }
        if (m.equals("ERR Request timed out") || m.equals("ERR timed out")) {
            return "ERR backend request timed out; the command may or may not have run";
        }
        return m;
    }

    private static String serverFormat(String msg) {
        if (msg == null || msg.isBlank()) {
            return "ERR Unknown error";
        }
        String m = msg;
        Matcher prefix;
        while ((prefix = EXCEPTION_PREFIX.matcher(m)).find()) {
            m = m.substring(prefix.end());
        }

        Matcher glide = GLIDE_KIND_ERROR.matcher(m);
        if (glide.matches()) {
            return withCode(GLIDE_KIND_CODES.getOrDefault(glide.group(1), "ERR"), glide.group(2));
        }
        Matcher bare = BARE_KIND_ERROR.matcher(m);
        if (bare.matches() && GLIDE_KIND_CODES.containsKey(bare.group(1))) {
            return withCode(GLIDE_KIND_CODES.get(bare.group(1)), bare.group(2));
        }
        Matcher code = CODE_COLON_ERROR.matcher(m);
        if (code.matches()) {
            return withCode(code.group(1), code.group(2));
        }
        if (CODE_SPACE_ERROR.matcher(m).matches()) {
            return m;
        }
        return "ERR " + m;
    }

    private static String withCode(String code, String detail) {
        // Avoid "ERR ERR ..." if the detail already carries the code.
        return detail.startsWith(code + " ") ? detail : code + " " + detail;
    }
}
