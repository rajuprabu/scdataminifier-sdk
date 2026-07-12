package com.scdataminifier.util;

import java.util.Arrays;

import com.scdataminifier.ScDataException;

/** Bounds-checked big-endian byte reader. */
public class ByteReader {

    private final byte[] data;
    private int pos;

    public ByteReader(byte[] data) {
        if (data == null) throw new ScDataException("Data is null");
        this.data = data;
    }

    private void check(int n) {
        if (pos + n > data.length) {
            throw new ScDataException("Unexpected end of data: need " + n
                    + " byte(s) at offset " + pos + " but only " + (data.length - pos) + " left");
        }
    }

    public int readByte() {
        check(1);
        return data[pos++] & 0xFF;
    }

    public int readShort() {
        check(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public long readLong5() {
        check(5);
        long v = 0;
        for (int i = 0; i < 5; i++) v = (v << 8) | (data[pos++] & 0xFF);
        return v;
    }

    public byte[] readBytes(int n) {
        check(n);
        byte[] out = Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return out;
    }

    public int position() { return pos; }

    public int remaining() { return data.length - pos; }

    public boolean hasMore() { return pos < data.length; }
}
