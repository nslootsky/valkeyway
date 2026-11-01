/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an inline command line ({@code PING}, {@code SET k "hello world"}) into arguments, following
 * Valkey 9's {@code sdssplitargs} ({@code sdsparsearg}): double quotes support
 * {@code \n \r \t \b \a \\ \" \xHH} escapes, single quotes only {@code \'}. A closing quote ends the
 * quoted part, not the argument, so quoted and unquoted parts join up: {@code "a"'b'c} is {@code abc}.
 * A quote left open (or a NUL byte inside quotes) is an error. [upstream:V11]
 */
final class InlineArgs {

    private InlineArgs() {}

    static byte[][] split(byte[] line) {
        List<byte[]> args = new ArrayList<>();
        int p = 0;
        int n = line.length;
        while (true) {
            while (p < n && isSpace(line[p])) {
                p++;
            }
            if (p >= n) {
                return args.toArray(byte[][]::new);
            }
            boolean inDouble = false;
            boolean inSingle = false;
            boolean done = false;
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            while (!done) {
                if (inDouble) {
                    if (p >= n || line[p] == 0) {
                        throw unbalanced();
                    }
                    byte c = line[p];
                    if (c == '\\' && p + 3 < n && line[p + 1] == 'x'
                            && isHex(line[p + 2]) && isHex(line[p + 3])) {
                        current.write(hex(line[p + 2]) * 16 + hex(line[p + 3]));
                        p += 3;
                    } else if (c == '\\' && p + 1 < n) {
                        p++;
                        current.write(switch (line[p]) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'b' -> '\b';
                            case 'a' -> 7;
                            default -> line[p];
                        });
                    } else if (c == '"') {
                        inDouble = false;
                    } else {
                        current.write(c);
                    }
                } else if (inSingle) {
                    if (p >= n || line[p] == 0) {
                        throw unbalanced();
                    }
                    byte c = line[p];
                    if (c == '\\' && p + 1 < n && line[p + 1] == '\'') {
                        p++;
                        current.write('\'');
                    } else if (c == '\'') {
                        inSingle = false;
                    } else {
                        current.write(c);
                    }
                } else {
                    if (p >= n) {
                        done = true;
                    } else {
                        byte c = line[p];
                        switch (c) {
                            case ' ', '\n', '\r', '\t', 0 -> done = true;
                            case '"' -> inDouble = true;
                            case '\'' -> inSingle = true;
                            default -> current.write(c);
                        }
                    }
                }
                if (p < n) {
                    p++;
                }
            }
            args.add(current.toByteArray());
        }
    }

    private static ProtocolException unbalanced() {
        return new ProtocolException("unbalanced quotes in request");
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    private static boolean isHex(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(byte c) {
        return c <= '9' ? c - '0' : (c | 0x20) - 'a' + 10;
    }
}
