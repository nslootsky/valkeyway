/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.reply;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Number formatting that matches what Valkey sends as text.
 *
 * <p>Relies on upstream behavior: [upstream:V1] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class DoubleFormat {

    private DoubleFormat() {}

    /**
     * Formats a double the way Valkey does for RESP2 replies (util.c d2string): exact integers
     * that fit comfortably in a long are printed as integers, everything else with the shortest
     * round-trip representation using fpconv's notation rules ("0.1", "1.5", "1e+20", "1e-7").
     */
    public static String valkey(double d) {
        if (java.lang.Double.isNaN(d)) {
            return "nan";
        }
        if (java.lang.Double.isInfinite(d)) {
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
        BigDecimal shortest = new BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros();
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

    /**
     * Formats like C's printf("%.Nf") on the exact binary value (round half to even), optionally
     * trimming trailing zeros the way Valkey's human-readable long double output does.
     */
    public static String fixed(double d, int decimals, boolean trimZeros) {
        BigDecimal rounded = new BigDecimal(d).setScale(decimals, RoundingMode.HALF_EVEN);
        if (trimZeros) {
            rounded = rounded.stripTrailingZeros();
            if (rounded.scale() < 0) {
                rounded = rounded.setScale(0);
            }
        }
        String s = rounded.toPlainString();
        return s.equals("-0") ? "0" : s;
    }
}
