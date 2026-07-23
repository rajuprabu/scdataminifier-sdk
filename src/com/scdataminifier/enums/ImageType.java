package com.scdataminifier.enums;

import com.scdataminifier.ScDataException;

public enum ImageType {
    CODEC_A(0),
    CODEC_B(1);

    private final int code;

    ImageType(int code) { this.code = code; }

    public int getCode() { return code; }

    public static ImageType fromCode(int code) {
        for (ImageType t : values()) {
            if (t.code == code) return t;
        }
        throw new ScDataException("Unknown image type code: " + code);
    }
}
