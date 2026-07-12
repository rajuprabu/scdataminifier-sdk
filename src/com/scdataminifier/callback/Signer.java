package com.scdataminifier.callback;

/**
 * Implemented by the application; the SDK never holds signing keys.
 * Called by ScDataWriter.build() when a signer was configured.
 */
public interface Signer {

    /**
     * @param dataToSign the exact bytes to sign (same bytes returned by
     *                   ScDataWriter.getBytesForSignature())
     * @return the signature bytes
     */
    byte[] sign(byte[] dataToSign);
}
