/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.protocol.RedisToken;
import com.github.tonivade.resp.protocol.SafeString;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for converting Glide results to RedisToken and cleaning error messages.
 */
public class TokenUtils {

    private TokenUtils() {}

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
     * Turns a Glide/Java exception message into a RESP error string in the server's format,
     * "CODE detail", so clients can dispatch on the code (WRONGTYPE, NOSCRIPT, CROSSSLOT, ...).
     */
    public static String cleanErrorMessage(String msg) {
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

    public static String summarize(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> s.length() > 50 ? s.substring(0, 50) + "..." : s;
            case Number n -> n.toString();
            case Object[] arr -> "[" + arr.length + "]";
            default -> value.toString();
        };
    }

    public static RedisToken toRedisToken(ClusterValue<Object> clusterValue) {
        if (clusterValue == null) {
            return RedisToken.nullString();
        }
        if (clusterValue.hasSingleData()) {
            Object val = clusterValue.getSingleValue();
            return toRedisToken(val);
        } else if (clusterValue.hasMultiData()) {
            Map<String, Object> multiValue = clusterValue.getMultiValue();
            return toRedisToken(multiValue);
        }
        return RedisToken.nullString();
    }

    public static RedisToken toRedisToken(Object value) {
        return switch (value) {
            case null -> RedisToken.nullString();
            case GlideString gs -> {
                byte[] bytes = gs.getBytes();
                yield RedisToken.string(new SafeString(bytes));
            }
            case String s -> {
                if (s.equals("OK")) {
                    yield RedisToken.responseOk();
                }
                yield RedisToken.string(s);
            }
            case Number n -> toRedisToken(n);
            case Boolean b -> RedisToken.integer(b ? 1 : 0);
            // Failed command inside an EXEC/batch result.
            case Throwable t -> RedisToken.error(cleanErrorMessage(t.getMessage()));
            case Object[] arr -> toRedisArray(arr);
            case Collection<?> list -> toRedisArray(list.toArray());
            case Map<?, ?> map -> {
                List<RedisToken> tokens = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    tokens.add(toRedisToken(entry.getKey()));
                    tokens.add(toRedisToken(entry.getValue()));
                }
                yield RedisToken.array(tokens);
            }
            default -> RedisToken.string(value.toString());
        };
    }

    /**
     * Integers become RESP integers; doubles (RESP3 double replies, e.g. ZSCORE) become bulk
     * strings, which is how RESP2 servers send them.
     */
    public static RedisToken toRedisToken(Number n) {
        return switch (n) {
            case Double d -> RedisToken.string(formatDouble(d));
            case Float f -> RedisToken.string(formatDouble(f.doubleValue()));
            case BigDecimal bd -> RedisToken.string(bd.toPlainString());
            default -> RedisToken.integer(n.intValue());
        };
    }

    /**
     * Formats a double the way Valkey does for RESP2 replies (util.c d2string): exact integers
     * that fit comfortably in a long are printed as integers, everything else with the shortest
     * round-trip representation using fpconv's notation rules ("0.1", "1.5", "1e+20", "1e-7").
     */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return "0";
        }
        if (d >= -Long.MAX_VALUE / 2 && d <= Long.MAX_VALUE / 2 && d == (long) d) {
            return Long.toString((long) d);
        }

        boolean neg = d < 0;
        // Double.toString yields the shortest digits that round-trip (JDK 19+).
        BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int ndigits = digits.length();
        int k = -shortest.scale();
        return (neg ? "-" : "") + emitDigits(digits, ndigits, k, neg);
    }

    /** Port of fpconv_dtoa's emit_digits: value = digits * 10^k. */
    private static String emitDigits(String digits, int ndigits, int k, boolean neg) {
        int exp = Math.abs(k + ndigits - 1);
        if (k >= 0 && exp < ndigits + 7) {
            return digits + "0".repeat(k);
        }
        if (k < 0 && (k > -7 || exp < 4)) {
            int offset = ndigits - Math.abs(k);
            if (offset <= 0) {
                return "0." + "0".repeat(-offset) + digits;
            }
            return digits.substring(0, offset) + "." + digits.substring(offset);
        }
        int n = Math.min(ndigits, 18 - (neg ? 1 : 0));
        StringBuilder sb = new StringBuilder();
        sb.append(digits.charAt(0));
        if (n > 1) {
            sb.append('.').append(digits, 1, n);
        }
        sb.append('e').append(k + ndigits - 1 < 0 ? '-' : '+').append(exp);
        return sb.toString();
    }

    public static RedisToken toRedisArray(Object[] values) {
        if (values == null) {
            return RedisToken.nullString();
        }
        List<RedisToken> tokens = new ArrayList<>();
        for (Object v : values) {
            tokens.add(toRedisToken(v));
        }
        return RedisToken.array(tokens);
    }
}
