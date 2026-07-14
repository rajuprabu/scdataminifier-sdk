package com.scdataminifier.qr;

import java.util.Arrays;

import com.scdataminifier.ScDataException;

/**
 * Reassembles a payload from multi-QR fragments scanned in any order (see
 * {@link MultiQr}). Feed each scanned QR's bytes to {@link #add(byte[])};
 * once {@link #isComplete()} is true, {@link #assemble()} returns the original
 * payload (fingerprint-verified) ready for ScDataParser.
 *
 * A single standalone code (magic 0x03) completes immediately. Fragments
 * (magic 0x05) accumulate; duplicates are ignored and fragments from a
 * different document (mismatched fingerprint) are reported as WRONG_SET.
 *
 * Not thread-safe; use one assembler per scanning session.
 *
 * <pre>
 * MultiQrAssembler asm = new MultiQrAssembler();
 * for (byte[] scanned : scans) {
 *     switch (asm.add(scanned)) {
 *         case ACCEPTED:  showProgress(asm.getReceivedCount(), asm.getTotal()); break;
 *         case DUPLICATE: // already had it
 *         case WRONG_SET: // belongs to another document
 *         case STANDALONE:
 *         case COMPLETED: byte[] payload = asm.assemble(); // -> ScDataParser.parse(payload)
 *     }
 * }
 * </pre>
 */
public final class MultiQrAssembler {

    public enum Status {
        /** A standalone single-QR payload was read; assembly is complete. */
        STANDALONE,
        /** Fragment stored; more are still needed. */
        ACCEPTED,
        /** Fragment already had this index; ignored. */
        DUPLICATE,
        /** Fragment belongs to a different payload set (fingerprint/total mismatch); ignored. */
        WRONG_SET,
        /** Fragment stored and the set is now complete. */
        COMPLETED
    }

    private byte[] fingerprint;
    private int total = -1;
    private byte[][] slots;
    private int received;
    private byte[] standalone;

    /** Convenience for numeric-mode scans: converts digits to bytes, then {@link #add(byte[])}. */
    public Status addNumeric(String digits) {
        return add(SCQrGenerator.numericToBytes(digits));
    }

    /**
     * Adds one scanned QR's decoded bytes. For numeric-mode QRs, convert the
     * scanned digit string with SCQrGenerator.numericToBytes first (or use
     * {@link #addNumeric(String)}).
     */
    public Status add(byte[] qrBytes) {
        if (qrBytes == null || qrBytes.length == 0) throw new ScDataException("Empty QR content");

        if (!MultiQr.isFragment(qrBytes)) {
            // Standalone payload (magic 0x03 or anything not a fragment).
            standalone = qrBytes.clone();
            return Status.STANDALONE;
        }

        MultiQr.Fragment f = MultiQr.parse(qrBytes);
        if (fingerprint == null) {
            fingerprint = f.fingerprint;
            total = f.total;
            slots = new byte[total][];
        } else if (!Arrays.equals(fingerprint, f.fingerprint) || total != f.total) {
            return Status.WRONG_SET;
        }

        int idx = f.index - 1;
        if (slots[idx] != null) return Status.DUPLICATE;
        slots[idx] = f.data;
        received++;
        return isComplete() ? Status.COMPLETED : Status.ACCEPTED;
    }

    public boolean isComplete() {
        return standalone != null || (total > 0 && received == total);
    }

    /** Total fragments expected (from the first fragment seen), or -1 if none yet / standalone. */
    public int getTotal() { return total; }

    public int getReceivedCount() { return received; }

    /** Payload fingerprint of the active set, or null if none yet. */
    public byte[] getFingerprint() { return fingerprint == null ? null : fingerprint.clone(); }

    /** 1-based indices still missing (empty when complete or standalone). */
    public int[] getMissingIndices() {
        if (slots == null) return new int[0];
        int missing = total - received;
        int[] out = new int[missing];
        for (int i = 0, k = 0; i < total; i++) {
            if (slots[i] == null) out[k++] = i + 1;
        }
        return out;
    }

    /** Reassembles and returns the payload; throws until {@link #isComplete()}. */
    public byte[] assemble() {
        if (standalone != null) return standalone.clone();
        if (!isComplete()) {
            throw new ScDataException("Incomplete: " + received + "/" + total
                    + " fragments; missing " + Arrays.toString(getMissingIndices()));
        }
        int totalLen = 0;
        for (byte[] s : slots) totalLen += s.length;
        byte[] payload = new byte[totalLen];
        int off = 0;
        for (byte[] s : slots) {
            System.arraycopy(s, 0, payload, off, s.length);
            off += s.length;
        }
        if (!Arrays.equals(MultiQr.fingerprint(payload), fingerprint)) {
            throw new ScDataException("Reassembled payload fingerprint mismatch - a fragment is corrupted; rescan");
        }
        return payload;
    }

    /** Clears all state to start a new scanning session. */
    public void reset() {
        fingerprint = null;
        total = -1;
        slots = null;
        received = 0;
        standalone = null;
    }
}
