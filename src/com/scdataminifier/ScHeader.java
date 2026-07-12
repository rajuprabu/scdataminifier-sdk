package com.scdataminifier;

import com.scdataminifier.enums.EncryptionType;

/** Parsed payload header (bytes 1-12, plus key version when encrypted). */
public class ScHeader {

    private final int version;
    private final int applicationId;
    private final long uniqueId;
    private final int minClientVersion;
    private final boolean encrypted;
    private final EncryptionType encryptionType;   // null when not encrypted
    private final int encryptionKeyVersion;        // 0 when not encrypted

    public ScHeader(int version, int applicationId, long uniqueId, int minClientVersion,
                    boolean encrypted, EncryptionType encryptionType, int encryptionKeyVersion) {
        this.version = version;
        this.applicationId = applicationId;
        this.uniqueId = uniqueId;
        this.minClientVersion = minClientVersion;
        this.encrypted = encrypted;
        this.encryptionType = encryptionType;
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    public int getVersion() { return version; }

    public int getApplicationId() { return applicationId; }

    public long getUniqueId() { return uniqueId; }

    public int getMinClientVersion() { return minClientVersion; }

    public boolean isEncrypted() { return encrypted; }

    public EncryptionType getEncryptionType() { return encryptionType; }

    public int getEncryptionKeyVersion() { return encryptionKeyVersion; }
}
