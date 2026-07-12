package com.scdataminifier.enums;

import com.scdataminifier.ScDataException;

/** Encryption is always AES-GCM with a 12-byte IV prefixed to the ciphertext. */
public enum EncryptionType {
    AES_256(0, 32),
    AES_128(1, 16);

    private final int code;
    private final int keyLength;

    EncryptionType(int code, int keyLength) {
        this.code = code;
        this.keyLength = keyLength;
    }

    public int getCode() { return code; }

    /** Required key length in bytes. */
    public int getKeyLength() { return keyLength; }

    public static EncryptionType fromCode(int code) {
        for (EncryptionType t : values()) {
            if (t.code == code) return t;
        }
        throw new ScDataException("Unknown encryption type code: " + code);
    }
}
