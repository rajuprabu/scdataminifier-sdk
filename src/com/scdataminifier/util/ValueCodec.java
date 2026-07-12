package com.scdataminifier.util;

import java.nio.charset.StandardCharsets;

import com.scdataminifier.ScDataException;

/** Encodes/decodes primitive values to their wire representation. */
public final class ValueCodec {

    private ValueCodec() {}

    public static byte[] fromInt(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    public static int toInt(byte[] b) {
        if (b.length != 4) throw new ScDataException("INTEGER value must be 4 bytes, got " + b.length);
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    public static byte[] fromFloat(float f) {
        return fromInt(Float.floatToIntBits(f));
    }

    public static float toFloat(byte[] b) {
        if (b.length != 4) throw new ScDataException("FLOAT value must be 4 bytes, got " + b.length);
        return Float.intBitsToFloat(toInt(b));
    }

    /** STRING type: one byte per character (Latin-1). */
    public static byte[] fromLatin1(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) {
                throw new ScDataException("Character '" + s.charAt(i)
                        + "' does not fit in a STRING (Latin-1) value; use UNICODE_STRING instead");
            }
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static String toLatin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /** CAPTION and UNICODE_STRING types. */
    public static byte[] fromUtf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String toUtf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
