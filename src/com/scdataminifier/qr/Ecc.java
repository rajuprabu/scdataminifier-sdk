package com.scdataminifier.qr;

/**
 * QR Code error-correction level. Higher levels recover from more damage
 * (smudges, curl, poor print) at the cost of less data capacity:
 *
 *   LOW      ~7% of codewords recoverable
 *   MEDIUM   ~15%
 *   QUARTILE ~25%
 *   HIGH     ~30%
 *
 * For labels read by standard 2D hardware scanners, QUARTILE or HIGH improves
 * first-read reliability. Declaration order (LOW, MEDIUM, QUARTILE, HIGH) is
 * significant - it indexes the QR capacity tables.
 */
public enum Ecc {
    LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);

    /** 2-bit format value stored in the symbol (not the same as the ordinal). */
    final int formatBits;

    Ecc(int formatBits) { this.formatBits = formatBits; }
}
