/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Users;
import io.github.nslootsky.valkeyway.server.Request;
import io.github.nslootsky.valkeyway.transaction.Transaction;
import io.github.nslootsky.valkeyway.transaction.WatchSession;
import io.github.nslootsky.valkeyway.transaction.WatchSessions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * CLIENT, answered entirely by the proxy from its own connections.
 *
 * <p>Nothing is forwarded: CLIENT acts on the connection that sends it, and the proxy's backend
 * connections are shared by every client, so forwarding would act on all of them at once (CLIENT
 * REPLY OFF silenced a node for everyone, CLIENT KILL hit other applications' connections).
 * Subcommands that only make sense on the server itself (PAUSE, TRACKING, UNBLOCK, ...) are rejected.
 * [upstream:G11]
 */
public class ClientCommand implements CommandHandler {

    private static final Set<String> UNSUPPORTED = Set.of(
            "pause", "unpause", "tracking", "caching", "getredir", "trackinginfo", "unblock", "capa",
            "import-source");

    private final WatchSessions watches;

    public ClientCommand(WatchSessions watches) {
        this.watches = watches;
    }

    @Override
    public InTransaction inTransaction() {
        return InTransaction.REJECT;
    }

    @Override
    public Reply execute(Request request) {
        if (request.argCount() < 1) {
            return Reply.error("ERR wrong number of arguments for 'client' command");
        }
        Connection connection = request.connection();
        String sub = request.argString(0).toLowerCase(Locale.ROOT);
        int n = request.argCount();
        return switch (sub) {
            case "id" -> n == 1 ? Reply.integer(connection.id()) : arity(sub);
            case "getname" -> n == 1
                    ? connection.name() == null ? Reply.Null.BULK : Reply.bulk(connection.name())
                    : arity(sub);
            case "setname" -> n == 2 ? setName(connection, request.argString(1)) : arity(sub);
            case "setinfo" -> n == 3 ? setInfo(connection, request.argString(1), request.argString(2)) : arity(sub);
            case "info" -> n == 1 ? Reply.bulk(line(connection) + "\n") : arity(sub);
            case "list" -> list(request);
            case "kill" -> n >= 2 ? kill(request) : arity(sub);
            case "reply" -> n == 2 ? reply(connection, request.argString(1)) : arity(sub);
            case "no-evict" -> n == 2 ? flag(request.argString(1), connection::noEvict) : arity(sub);
            case "no-touch" -> n == 2 ? flag(request.argString(1), connection::noTouch) : arity(sub);
            case "help" -> help();
            default -> UNSUPPORTED.contains(sub)
                    ? Reply.error("ERR CLIENT " + sub.toUpperCase(Locale.ROOT) + " is not supported by this proxy")
                    : Reply.error("ERR unknown subcommand '" + truncate(request.argString(0)) + "'. Try CLIENT HELP.");
        };
    }

    private static Reply arity(String sub) {
        return Reply.error("ERR wrong number of arguments for 'client|" + sub + "' command");
    }

    static Reply setName(Connection connection, String name) {
        if (hasSpecialChars(name)) {
            return Reply.error("ERR Client names cannot contain spaces, newlines or special characters.");
        }
        connection.name(name.isEmpty() ? null : name);
        return Reply.OK;
    }

    private static Reply setInfo(Connection connection, String attribute, String value) {
        if (hasSpecialChars(value)) {
            return Reply.error("ERR " + attribute + " cannot contain spaces, newlines or special characters.");
        }
        switch (attribute.toLowerCase(Locale.ROOT)) {
            case "lib-name" -> connection.libName(value);
            case "lib-ver" -> connection.libVersion(value);
            default -> {
                return Reply.error("ERR Unrecognized option '" + attribute + "'");
            }
        }
        return Reply.OK;
    }

    /** Spaces, newlines or anything outside printable ASCII, which Valkey rejects in names. */
    private static boolean hasSpecialChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) <= ' ' || s.charAt(i) > '~') {
                return true;
            }
        }
        return false;
    }

    private static Reply reply(Connection connection, String mode) {
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "on" -> connection.replyMode(Connection.ReplyMode.ON);
            case "off" -> connection.replyMode(Connection.ReplyMode.OFF);
            case "skip" -> connection.replyMode(Connection.ReplyMode.SKIP);
            default -> {
                return Reply.error("ERR syntax error");
            }
        }
        // For OFF and SKIP the connection drops this reply itself.
        return Reply.OK;
    }

    private static Reply flag(String value, java.util.function.Consumer<Boolean> set) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> set.accept(true);
            case "off" -> set.accept(false);
            default -> {
                return Reply.error("ERR syntax error");
            }
        }
        return Reply.OK;
    }

    // --- LIST / KILL ---

    /** CLIENT LIST [TYPE normal|master|replica|pubsub] [ID id ...] */
    private Reply list(Request request) {
        Predicate<Connection> filter = _ -> true;
        for (int i = 1; i < request.argCount(); i++) {
            String option = request.argString(i).toLowerCase(Locale.ROOT);
            if (option.equals("type") && i + 1 < request.argCount()) {
                String given = request.argString(++i);
                String type = given.toLowerCase(Locale.ROOT);
                if (!Set.of("normal", "master", "replica", "slave", "pubsub").contains(type)) {
                    return Reply.error("ERR Unknown client type '" + given + "'");
                }
                // Every proxy client is a normal one.
                if (!type.equals("normal")) {
                    filter = _ -> false;
                }
            } else if (option.equals("id") && i + 1 < request.argCount()) {
                Set<Long> ids = new HashSet<>();
                while (i + 1 < request.argCount()) {
                    try {
                        ids.add(Long.parseLong(request.argString(++i)));
                    } catch (NumberFormatException e) {
                        return Reply.error("ERR syntax error");
                    }
                }
                Predicate<Connection> previous = filter;
                filter = c -> previous.test(c) && ids.contains(c.id());
            } else {
                return Reply.error("ERR syntax error");
            }
        }
        StringBuilder out = new StringBuilder();
        for (Connection c : sorted(request.connection())) {
            if (filter.test(c)) {
                out.append(line(c)).append('\n');
            }
        }
        return Reply.bulk(out.toString());
    }

    /**
     * CLIENT KILL ip:port (old form: +OK or an error), or CLIENT KILL filter value ... (new form: the
     * number killed). Filters: ID, TYPE, USER, ADDR, LADDR, SKIPME yes|no, MAXAGE seconds.
     */
    private Reply kill(Request request) {
        Connection self = request.connection();
        if (request.argCount() == 2) {
            String addr = request.argString(1);
            for (Connection c : self.registry().clients()) {
                if (address(c.remoteAddress()).equals(addr)) {
                    kill(self, c);
                    return Reply.OK;
                }
            }
            return Reply.error("ERR No such client");
        }
        if ((request.argCount() - 1) % 2 != 0) {
            return Reply.error("ERR syntax error");
        }
        List<Predicate<Connection>> filters = new ArrayList<>();
        boolean skipMe = true;
        for (int i = 1; i < request.argCount(); i += 2) {
            String option = request.argString(i).toLowerCase(Locale.ROOT);
            String value = request.argString(i + 1);
            switch (option) {
                case "id" -> {
                    long id;
                    try {
                        id = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return Reply.error("ERR syntax error");
                    }
                    if (id <= 0) {
                        return Reply.error("ERR client-id should be greater than 0");
                    }
                    filters.add(c -> c.id() == id);
                }
                case "type" -> {
                    String type = value.toLowerCase(Locale.ROOT);
                    if (!Set.of("normal", "master", "replica", "slave", "pubsub").contains(type)) {
                        return Reply.error("ERR Unknown client type '" + value + "'");
                    }
                    filters.add(_ -> type.equals("normal"));
                }
                case "user" -> filters.add(c -> value.equals(userOf(c)));
                case "addr" -> filters.add(c -> address(c.remoteAddress()).equals(value));
                case "laddr" -> filters.add(c -> address(c.localAddress()).equals(value));
                case "skipme" -> {
                    switch (value.toLowerCase(Locale.ROOT)) {
                        case "yes" -> skipMe = true;
                        case "no" -> skipMe = false;
                        default -> {
                            return Reply.error("ERR syntax error");
                        }
                    }
                }
                case "maxage" -> {
                    long seconds;
                    try {
                        seconds = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return Reply.error("ERR syntax error");
                    }
                    filters.add(c -> c.ageMillis() / 1000 >= seconds);
                }
                default -> {
                    return Reply.error("ERR syntax error");
                }
            }
        }
        long killed = 0;
        for (Connection c : self.registry().clients()) {
            if ((skipMe && c == self) || !filters.stream().allMatch(f -> f.test(c))) {
                continue;
            }
            kill(self, c);
            killed++;
        }
        return Reply.integer(killed);
    }

    /** Kills {@code target}; a client killing itself gets its reply first, as from the server. */
    private static void kill(Connection self, Connection target) {
        if (target == self) {
            self.closeAfterReply();
        } else {
            target.kill();
        }
    }

    private static List<Connection> sorted(Connection self) {
        List<Connection> all = new ArrayList<>(self.registry().clients());
        all.sort(Comparator.comparingLong(Connection::id));
        return all;
    }

    /**
     * One client in CLIENT LIST format, with the fields Valkey 9 prints. Values the proxy doesn't track
     * (buffers, memory, network totals) are 0; every client is a RESP2 client.
     */
    String line(Connection c) {
        boolean multi = c.inTransaction();
        WatchSession watch = watches.current(c);
        StringBuilder flags = new StringBuilder();
        if (multi) {
            flags.append('x');
        }
        if (c.noEvict()) {
            flags.append('e');
        }
        if (c.noTouch()) {
            flags.append('T');
        }
        if (flags.isEmpty()) {
            flags.append('N');
        }
        byte[] last = c.lastCommand();
        return "id=" + c.id()
                + " addr=" + address(c.remoteAddress())
                + " laddr=" + address(c.localAddress())
                + " fd=-1"
                + " name=" + (c.name() == null ? "" : c.name())
                + " age=" + c.ageMillis() / 1000
                + " idle=" + c.idleMillis() / 1000
                + " flags=" + flags
                + " capa="
                + " db=" + c.db()
                + " sub=0 psub=0 ssub=0"
                + " multi=" + (multi ? Transaction.current(c).commands().size() : -1)
                + " watch=" + (watch == null ? 0 : watch.keyCount())
                + " qbuf=0 qbuf-free=0 argv-mem=0 multi-mem=0 rbs=0 rbp=0 obl=0 oll=0 omem=0 tot-mem=0"
                + " events=r"
                + " cmd=" + (last == null ? "NULL" : new String(last, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT))
                + " user=" + userOf(c) + " redir=-1 resp=2"
                + " lib-name=" + c.libName()
                + " lib-ver=" + c.libVersion()
                + " tot-net-in=0 tot-net-out=0"
                + " tot-cmds=" + c.totalCommands();
    }

    /** As Valkey, a connection that hasn't authenticated yet shows as the default user. */
    private static String userOf(Connection c) {
        String user = c.user();
        return user == null ? Users.DEFAULT : user;
    }

    private static String address(InetSocketAddress address) {
        if (address == null) {
            return "";
        }
        String host = address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    private static String truncate(String s) {
        return s.length() <= 128 ? s : s.substring(0, 128);
    }

    private static Reply help() {
        String[] lines = {
                "CLIENT <subcommand> [<arg> [value] [opt] ...]. Subcommands are:",
                "ID", "    Return the ID of the current connection.",
                "INFO", "    Return information about the current client connection.",
                "LIST [TYPE <normal|master|replica|pubsub>] [ID <id> [<id> ...]]",
                "    Return information about client connections.",
                "KILL <ip:port> | KILL <option> <value> [<option> <value> ...]",
                "    Kill connections. Options: ID, TYPE, USER, ADDR, LADDR, SKIPME, MAXAGE.",
                "GETNAME", "    Return the name of the current connection.",
                "SETNAME <name>", "    Assign the name <name> to the current connection.",
                "SETINFO <LIB-NAME|LIB-VER> <value>", "    Set client library name or version.",
                "REPLY (ON|OFF|SKIP)", "    Control the replies sent to the current connection.",
                "NO-EVICT (ON|OFF)", "NO-TOUCH (ON|OFF)", "    Accepted; the proxy keeps the flags for CLIENT LIST.",
                "HELP", "    Print this help.",
                "Not supported by this proxy: PAUSE, UNPAUSE, TRACKING, CACHING, GETREDIR, TRACKINGINFO, UNBLOCK.",
        };
        List<Reply> replies = new ArrayList<>(lines.length);
        for (String line : lines) {
            replies.add(new Reply.Simple(line));
        }
        return new Reply.Array(replies);
    }
}
