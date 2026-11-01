/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

/**
 * Valkey's glob matching ({@code stringmatchlen} in util.c), case-sensitive, as PSUBSCRIBE patterns
 * match channels: {@code *}, {@code ?}, {@code [abc]}, {@code [^a-z]} and {@code \} escapes.
 */
final class Glob {

    private Glob() {}

    static boolean matches(byte[] pattern, byte[] string) {
        return match(pattern, 0, pattern.length, string, 0, string.length, new boolean[1], 0);
    }

    /** {@code skipLonger} is Valkey's skipLongerMatches: once a '*' fails everywhere, earlier ones can't help. */
    private static boolean match(byte[] pattern, int p, int pLen, byte[] string, int s, int sLen,
                                 boolean[] skipLonger, int nesting) {
        if (nesting > 1000) {
            return false; // protection against abusive patterns, as in Valkey
        }
        while (pLen > 0 && sLen > 0) {
            switch (pattern[p]) {
                case '*' -> {
                    while (pLen > 1 && pattern[p + 1] == '*') {
                        p++;
                        pLen--;
                    }
                    if (pLen == 1) {
                        return true;
                    }
                    while (sLen > 0) {
                        if (match(pattern, p + 1, pLen - 1, string, s, sLen, skipLonger, nesting + 1)) {
                            return true;
                        }
                        if (skipLonger[0]) {
                            return false;
                        }
                        s++;
                        sLen--;
                    }
                    skipLonger[0] = true;
                    return false;
                }
                case '?' -> {
                    s++;
                    sLen--;
                }
                case '[' -> {
                    p++;
                    pLen--;
                    boolean not = pLen > 0 && pattern[p] == '^';
                    if (not) {
                        p++;
                        pLen--;
                    }
                    boolean matched = false;
                    while (true) {
                        if (pLen >= 2 && pattern[p] == '\\') {
                            p++;
                            pLen--;
                            if (pattern[p] == string[s]) {
                                matched = true;
                            }
                        } else if (pLen == 0) {
                            p--;
                            pLen++;
                            break;
                        } else if (pattern[p] == ']') {
                            break;
                        } else if (pLen >= 3 && pattern[p + 1] == '-') {
                            int start = pattern[p];
                            int end = pattern[p + 2];
                            int c = string[s];
                            if (start > end) {
                                int t = start;
                                start = end;
                                end = t;
                            }
                            p += 2;
                            pLen -= 2;
                            if (c >= start && c <= end) {
                                matched = true;
                            }
                        } else if (pattern[p] == string[s]) {
                            matched = true;
                        }
                        p++;
                        pLen--;
                    }
                    if (not) {
                        matched = !matched;
                    }
                    if (!matched) {
                        return false;
                    }
                    s++;
                    sLen--;
                }
                default -> {
                    if (pattern[p] == '\\' && pLen >= 2) {
                        p++;
                        pLen--;
                    }
                    if (pattern[p] != string[s]) {
                        return false;
                    }
                    s++;
                    sLen--;
                }
            }
            p++;
            pLen--;
            if (sLen == 0) {
                while (pLen > 0 && pattern[p] == '*') {
                    p++;
                    pLen--;
                }
                break;
            }
        }
        return pLen == 0 && sLen == 0;
    }
}
