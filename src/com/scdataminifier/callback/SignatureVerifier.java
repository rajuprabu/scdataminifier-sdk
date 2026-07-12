package com.scdataminifier.callback;

import com.scdataminifier.ScHeader;
import com.scdataminifier.enums.SignatureAlgorithm;

/**
 * Implemented by the application; the SDK never holds verification keys.
 * Called during parsing when the payload carries a digital signature.
 */
public interface SignatureVerifier {

    /**
     * @param header     parsed header (use application ID / unique ID plus
     *                   keyVersion to select the public key)
     * @param algorithm  signature algorithm from the payload
     * @param keyVersion signature key version from the payload (0-15)
     * @param signedData the exact bytes the signature covers
     * @param signature  the signature bytes
     * @return true if the signature is valid; false makes the parser throw
     */
    boolean verify(ScHeader header, SignatureAlgorithm algorithm, int keyVersion,
                   byte[] signedData, byte[] signature);
}
