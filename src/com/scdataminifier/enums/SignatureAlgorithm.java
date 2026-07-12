package com.scdataminifier.enums;

import com.scdataminifier.ScDataException;

public enum SignatureAlgorithm {
    ECDSA_P256_SHA256(0, "SHA256withECDSA"),
    RSA_2048_SHA256(1, "SHA256withRSA"),
    ECDSA_P384_SHA384(2, "SHA384withECDSA"),
    /** No signature; the payload carries a CRC16 instead. */
    NONE(7, null);

    private final int code;
    private final String jcaName;

    SignatureAlgorithm(int code, String jcaName) {
        this.code = code;
        this.jcaName = jcaName;
    }

    public int getCode() { return code; }

    /** Algorithm name for java.security.Signature.getInstance(), null for NONE. */
    public String getJcaName() { return jcaName; }

    public static SignatureAlgorithm fromCode(int code) {
        for (SignatureAlgorithm t : values()) {
            if (t.code == code) return t;
        }
        throw new ScDataException("Unknown signature algorithm code: " + code);
    }
}
