package com.scdataminifier.callback;

import com.scdataminifier.ScHeader;

/**
 * Implemented by the application; the SDK never holds encryption keys.
 * Called by ScDataWriter.build() when encryption was configured.
 * CryptoUtil.aesGcmEncrypt() is a ready-made implementation body for
 * applications that hold a raw AES key.
 */
public interface EncryptionCallback {

    /**
     * @param header    the header being written (application ID, unique ID,
     *                  encryption type and key version - everything needed to
     *                  select the key)
     * @param plaintext the plaintext region to encrypt
     * @return 12-byte GCM IV followed by ciphertext+tag
     */
    byte[] encrypt(ScHeader header, byte[] plaintext);
}
