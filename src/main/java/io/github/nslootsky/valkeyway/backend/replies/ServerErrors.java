/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.replies;

import java.util.Set;
import java.util.concurrent.CompletionException;
import glide.api.models.exceptions.ClosingException;
import glide.api.models.exceptions.TimeoutException;
import io.github.nslootsky.valkeyway.protocol.Reply;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Glide/Java exceptions into error replies in the server's format, "CODE detail", so
 * clients can dispatch on the code (WRONGTYPE, NOSCRIPT, CROSSSLOT, ...).
 *
 * <p>Relies on upstream behavior: [upstream:G1] [upstream:G2] [upstream:G3] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class ServerErrors {

    private ServerErrors() {}

    // [upstream:G1] Glide error message formats.

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

    /**
     * Any other "Code: detail" is a server error code Glide doesn't know (an ExtensionError), such as
     * the search module's "Index", unless the code is one of Glide's own error kinds or a Rust I/O
     * error kind ("BrokenPipe: ..." when a node's connection drops).
     */
    private static final Pattern EXTENSION_ERROR = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*): (.*)$", Pattern.DOTALL);

    /** Glide's error kind names (redis-rs {@code types.rs}, {@code ErrorKind}) and Rust's I/O ones: never server codes. */
    private static final Set<String> GLIDE_KINDS = Set.of("ResponseError", "ParseError", "AuthenticationFailed",
            "PermissionDenied", "TypeError", "ExecAbortError", "BusyLoadingError", "NoScriptError",
            "InvalidClientConfig", "Moved", "Ask", "TryAgain", "ClusterDown", "CrossSlot", "MasterDown", "IoError",
            "FatalSendError", "FatalReceiveError", "ClientError", "CircuitBreakerOpen", "ExtensionError", "ReadOnly",
            "MasterNameNotFoundBySentinel", "NoValidReplicasFoundBySentinel", "EmptySentinelList", "NotBusy",
            "AllConnectionsUnavailable", "ConnectionNotFoundForRoute", "RESP", "NotAllSlotsCovered",
            "UserOperationError", "ProtocolDesync",
            // Rust's std::io::ErrorKind
            "NotFound", "ConnectionRefused", "ConnectionReset", "HostUnreachable", "NetworkUnreachable",
            "ConnectionAborted", "NotConnected", "AddrInUse", "AddrNotAvailable", "NetworkDown", "BrokenPipe",
            "AlreadyExists", "WouldBlock", "NotADirectory", "IsADirectory", "DirectoryNotEmpty",
            "ReadOnlyFilesystem", "FilesystemLoop", "StaleNetworkFileHandle", "InvalidInput", "InvalidData",
            "TimedOut", "WriteZero", "StorageFull", "NotSeekable", "FilesystemQuotaExceeded", "QuotaExceeded",
            "FileTooLarge", "ResourceBusy", "ExecutableFileBusy", "Deadlock", "CrossesDevices", "TooManyLinks",
            "InvalidFilename", "ArgumentListTooLong", "Interrupted", "Unsupported", "UnexpectedEof",
            "OutOfMemory", "InProgress", "Other", "Uncategorized");

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
            Map.entry("NotBusy", "NOTBUSY"),
            // An ACL refusal on the node (e.g. the proxy's backend user lacks @admin)
            Map.entry("PermissionDenied", "NOPERM"));

    /**
     * Error codes with which a cluster node rejects a command without running it, and which Glide
     * retries for a single command (waiting, refreshing slots or following the redirect). Inside a
     * batch Glide returns them, except MOVED/ASK when the redirect target is reachable.
     */
    // [upstream:G3]
    private static final Set<String> REJECTED_UNRUN =
            Set.of("TRYAGAIN", "CLUSTERDOWN", "MASTERDOWN", "LOADING", "READONLY", "MOVED", "ASK");

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
    private static final String CLUSTER_DOWN = "CLUSTERDOWN ";

    /** An error reply for {@code e}, unwrapping {@link ExecutionException}. */
    public static Reply error(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        // [upstream:G2] Glide's few typed failures; everything else is text.
        return switch (cause) {
            case TimeoutException _ -> new Reply.Error(TIMED_OUT);
            case ClosingException _ ->
                    new Reply.Error(BACKEND_UNAVAILABLE + "the backend client is closed" + MAY_HAVE_RUN);
            default -> new Reply.Error(message(cause.getMessage()));
        };
    }

    /**
     * Whether {@code e} is a MOVED/ASK that Glide returned instead of following. The node rejected the
     * command without running it. [upstream:G10]
     */
    public static boolean isUnfollowedRedirect(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null && UNFOLLOWED_REDIRECT.matcher(serverFormat(cause.getMessage())).matches();
    }

    private static final String MAY_HAVE_RUN = "; the command may or may not have run";
    private static final String TIMED_OUT = "ERR backend request timed out" + MAY_HAVE_RUN;

    /** Converts a Glide/Java exception message to "CODE detail". */
    public static String message(String msg) {
        String server = serverFormat(msg);
        String rewritten = backendFailure(server);
        if (log.isDebugEnabled() && !rewritten.equals(server)) {
            log.debug("Backend failure \"{}\" sent to the client as \"{}\"", msg, rewritten);
        }
        return rewritten;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ServerErrors.class);

    /** A redirect Glide returned because it couldn't follow it: "MOVED 3739 127.0.0.1:7003; ExtensionError". */
    private static final Pattern UNFOLLOWED_REDIRECT = Pattern.compile("^(?:MOVED|ASK) (\\d+) (\\S+?);?(?: .*)?$");

    /** Glide's "no connection to this node (yet)", e.g. "... ConnectionNotFoundForRoute: 127.0.0.1:7003`...". */
    private static final Pattern NO_CONNECTION = Pattern.compile("ConnectionNotFoundForRoute: ([^`\\s]+)");

    /**
     * Rewrites Glide's messages for an unreachable or lost node, which mean nothing to a client of a
     * standalone server (a raw MOVED even makes Jedis throw JedisMovedDataException), as
     * "ERR backend unavailable: ...", saying whether the command may have run.
     */
    // [upstream:G2]
    private static String backendFailure(String m) {
        Matcher redirect = UNFOLLOWED_REDIRECT.matcher(m);
        if (redirect.matches()) {
            return BACKEND_UNAVAILABLE + "can't reach " + redirect.group(2) + " (slot " + redirect.group(1) + ")"
                    + DID_NOT_RUN;
        }
        if (m.startsWith(CLUSTER_DOWN)) {
            // The cluster can't serve the slot right now (a failover in progress, or a slot without a
            // primary), and the node refused the command without running it. Nothing a client of a
            // standalone server can make sense of.
            return BACKEND_UNAVAILABLE + "the cluster can't serve this command (" + m.substring(CLUSTER_DOWN.length())
                    + ")" + DID_NOT_RUN;
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
            return BACKEND_UNAVAILABLE + "lost the connection to a cluster node" + MAY_HAVE_RUN;
        }
        // One command's timeout inside a batch result is text, not a TimeoutException.
        if (m.equals("ERR timed out")) {
            return TIMED_OUT;
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
        Matcher extension = EXTENSION_ERROR.matcher(m);
        if (extension.matches() && !GLIDE_KINDS.contains(extension.group(1))) {
            return withCode(extension.group(1), extension.group(2));
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
