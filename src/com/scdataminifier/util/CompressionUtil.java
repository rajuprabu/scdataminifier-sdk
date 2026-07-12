package com.scdataminifier.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.CompressionType;

public final class CompressionUtil {

    private CompressionUtil() {}

    public static byte[] compress(byte[] data, CompressionType type) {
        return compress(data, type, null);
    }

    public static byte[] compress(byte[] data, CompressionType type, byte[] dictionary) {
        switch (type) {
            case ZIP:
                return deflate(data, null);
            case ZIP_DICT:
                if (dictionary == null) throw new ScDataException("ZIP_DICT compression requires a dictionary");
                return deflate(data, dictionary);
            case GZIP:
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 16);
                    GZIPOutputStream gz = new GZIPOutputStream(bos);
                    gz.write(data);
                    gz.close();
                    return bos.toByteArray();
                } catch (IOException e) {
                    throw new ScDataException("GZIP compression failed", e);
                }
            default:
                throw new ScDataException("Unsupported compression type: " + type);
        }
    }

    public static byte[] decompress(byte[] data, CompressionType type) {
        return decompress(data, type, null);
    }

    public static byte[] decompress(byte[] data, CompressionType type, byte[] dictionary) {
        switch (type) {
            case ZIP:
                return inflate(data, null);
            case ZIP_DICT:
                if (dictionary == null) throw new ScDataException("ZIP_DICT decompression requires a dictionary");
                return inflate(data, dictionary);
            case GZIP:
                try {
                    GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3 + 16);
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = gz.read(buf)) > 0) bos.write(buf, 0, n);
                    return bos.toByteArray();
                } catch (IOException e) {
                    throw new ScDataException("GZIP decompression failed", e);
                }
            default:
                throw new ScDataException("Unsupported compression type: " + type);
        }
    }

    /** Raw DEFLATE (no zlib wrapper), optionally with a preset dictionary. */
    private static byte[] deflate(byte[] data, byte[] dictionary) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            if (dictionary != null) deflater.setDictionary(dictionary);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 16);
            byte[] buf = new byte[1024];
            while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf));
            return bos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data, byte[] dictionary) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            // Raw inflate never signals needsDictionary(); preset it up front.
            if (dictionary != null) inflater.setDictionary(dictionary);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3 + 16);
            byte[] buf = new byte[1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new ScDataException("Truncated or dictionary-mismatched compressed data");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (DataFormatException e) {
            throw new ScDataException("Decompression failed", e);
        } finally {
            inflater.end();
        }
    }
}
