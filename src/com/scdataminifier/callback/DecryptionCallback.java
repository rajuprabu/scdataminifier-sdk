package com.scdataminifier.callback;

import com.scdataminifier.ScHeader;

/**
 * Implemented by the application; the SDK never holds decryption keys.
 * Called during parsing when the payload's encryption flag is set.
 */
public interface DecryptionCallback {

    /**
     * @param header          parsed header (application ID, unique ID, encryption
     *                        type and key version - everything needed to select the key)
     * @param ivAndCiphertext 12-byte GCM IV followed by ciphertext+tag
     * @return the decrypted plaintext bytes
     */
    byte[] decrypt(ScHeader header, byte[] ivAndCiphertext);
}
