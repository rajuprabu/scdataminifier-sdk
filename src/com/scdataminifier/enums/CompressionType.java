package com.scdataminifier.enums;

import com.scdataminifier.ScDataException;

public enum CompressionType {
    ZIP(0),      // raw DEFLATE (no zlib/gzip wrapper)
    GZIP(1),     // GZIP stream (interop; 18-byte overhead)
    ZIP_DICT(2); // raw DEFLATE with a shared preset dictionary (best for small payloads)

    private final int code;

    CompressionType(int code) { this.code = code; }

    public int getCode() { return code; }

    public static CompressionType fromCode(int code) {
        for (CompressionType t : values()) {
            if (t.code == code) return t;
        }
        throw new ScDataException("Unknown/unsupported compression type code: " + code);
    }
}
