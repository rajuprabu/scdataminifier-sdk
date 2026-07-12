package com.scdataminifier.util;

/** CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF). */
public final class Crc16 {

    private Crc16() {}

    public static int compute(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
