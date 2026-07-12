package com.scdataminifier.enums;

import com.scdataminifier.ScDataException;

/**
 * Value type codes, shared by TLV tags, table cells and variables
 * (one enum everywhere so the codes cannot diverge).
 */
public enum TagType {
    CAPTION(0),         // UTF-8 text used as a label / column header
    STRING(1),          // Latin-1 (single byte per char) text
    INTEGER(2),         // 4-byte signed big-endian
    FLOAT(3),           // 4-byte IEEE-754
    IMAGE(4),           // raw image bytes (PNG/JPEG - format self-described)
    BIOMETRIC(5),       // application-defined biometric template bytes
    TABLE(6),           // nested table structure (TLV only, max 16x16)
    UNICODE_STRING(7);  // UTF-8 text

    private final int code;

    TagType(int code) { this.code = code; }

    public int getCode() { return code; }

    public static TagType fromCode(int code) {
        for (TagType t : values()) {
            if (t.code == code) return t;
        }
        throw new ScDataException("Unknown tag type code: " + code);
    }
}
