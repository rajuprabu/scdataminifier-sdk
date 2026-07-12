package com.scdataminifier.enums;

import com.scdataminifier.ScDataException;

public enum ContentType {
    VARIABLES(0),
    TLV(1),
    COMPRESSED_TLV(2);

    private final int code;

    ContentType(int code) { this.code = code; }

    public int getCode() { return code; }

    public static ContentType fromCode(int code) {
        for (ContentType t : values()) {
            if (t.code == code) return t;
        }
        throw new ScDataException("Unknown content type code: " + code);
    }
}
