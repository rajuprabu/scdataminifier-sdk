package com.scdataminifier.util;

import java.util.Arrays;

/** Growable big-endian byte buffer. */
public class ByteWriter {

    private byte[] buf = new byte[128];
    private int size;

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            int n = buf.length * 2;
            while (n < size + extra) n *= 2;
            buf = Arrays.copyOf(buf, n);
        }
    }

    public ByteWriter writeByte(int b) {
        ensure(1);
        buf[size++] = (byte) b;
        return this;
    }

    public ByteWriter writeShort(int v) {
        ensure(2);
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) v;
        return this;
    }

    public ByteWriter writeLong5(long v) {
        ensure(5);
        for (int i = 4; i >= 0; i--) buf[size++] = (byte) (v >>> (8 * i));
        return this;
    }

    public ByteWriter writeBytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, size, b.length);
        size += b.length;
        return this;
    }

    public int size() { return size; }

    public byte[] toBytes() { return Arrays.copyOf(buf, size); }
}
