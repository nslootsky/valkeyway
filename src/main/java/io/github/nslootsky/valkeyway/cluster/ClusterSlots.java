/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.cluster;

/**
 * Valkey/Redis cluster hash slot calculation: CRC16 (XMODEM) of the key modulo 16384,
 * honouring {hash tags} as described in the cluster specification.
 */
public final class ClusterSlots {

    public static final int SLOT_COUNT = 16384;

    private static final int[] CRC16_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            CRC16_TABLE[i] = crc & 0xFFFF;
        }
    }

    private ClusterSlots() {}

    public static int slot(byte[] key) {
        int start = 0;
        int end = key.length;
        int open = indexOf(key, (byte) '{', 0);
        if (open >= 0) {
            int close = indexOf(key, (byte) '}', open + 1);
            // Only a non-empty tag counts; "{}" hashes the whole key.
            if (close > open + 1) {
                start = open + 1;
                end = close;
            }
        }
        return crc16(key, start, end) & (SLOT_COUNT - 1);
    }

    static int crc16(byte[] bytes, int start, int end) {
        int crc = 0;
        for (int i = start; i < end; i++) {
            crc = ((crc << 8) ^ CRC16_TABLE[((crc >>> 8) ^ bytes[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    private static int indexOf(byte[] bytes, byte target, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
