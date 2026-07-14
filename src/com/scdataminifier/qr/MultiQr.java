package com.scdataminifier.qr;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.scdataminifier.ScDataException;

/**
 * Multi-QR transport framing: a large SCDataMinifier payload (already signed
 * and/or encrypted) is split into fragments, each wrapped in a small envelope
 * and rendered as one QR. Fragments can be scanned in any order and
 * reassembled by {@link MultiQrAssembler}.
 *
 * The single/multi distinction is the first byte (the "tag"):
 *   0x03  = a standalone complete payload (SCDataMinifier magic) - parse directly.
 *   0x05  = a multi-QR fragment - collect until all are present, then reassemble.
 *
 * Fragment envelope (big-endian):
 * <pre>
 *   byte 0      magic = 0x05
 *   byte 1      envelope version = 1
 *   byte 2-5    payload fingerprint (SHA-256 of the whole payload, first 4 bytes)
 *   byte 6      total fragments N   (1-255)
 *   byte 7      fragment index i    (1..N)
 *   byte 8-9    fragment data length L (uint16)
 *   byte 10..   fragment data (L raw bytes of the payload slice)
 * </pre>
 * The fingerprint is identical on every fragment of one payload and serves
 * three purposes: set identity (ties fragments together), mix detection
 * (fragments of a different document are rejected), and integrity (the
 * reassembled bytes are re-fingerprinted before parsing).
 */
public final class MultiQr {

    public static final int FRAGMENT_MAGIC = 0x05;
    /** SCDataMinifier payload magic - a standalone (single-QR) code. */
    public static final int STANDALONE_MAGIC = 0x03;
    public static final int ENVELOPE_VERSION = 1;
    public static final int FINGERPRINT_LEN = 4;
    public static final int HEADER_LEN = 10; // magic + ver + fingerprint(4) + N + index + len(2)
    public static final int MAX_FRAGMENTS = 255;

    private MultiQr() {}

    /** First {@link #FINGERPRINT_LEN} bytes of SHA-256(payload). */
    public static byte[] fingerprint(byte[] payload) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(payload);
            return Arrays.copyOfRange(h, 0, FINGERPRINT_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new ScDataException("SHA-256 not available", e);
        }
    }

    static byte[] wrap(byte[] fingerprint, int total, int index, byte[] payload, int off, int len) {
        if (len > 0xFFFF) throw new ScDataException("Fragment data exceeds 65535 bytes");
        byte[] env = new byte[HEADER_LEN + len];
        env[0] = (byte) FRAGMENT_MAGIC;
        env[1] = (byte) ENVELOPE_VERSION;
        System.arraycopy(fingerprint, 0, env, 2, FINGERPRINT_LEN);
        env[6] = (byte) total;
        env[7] = (byte) index;
        env[8] = (byte) (len >>> 8);
        env[9] = (byte) len;
        System.arraycopy(payload, off, env, HEADER_LEN, len);
        return env;
    }

    /** A parsed fragment envelope. */
    public static final class Fragment {
        public final byte[] fingerprint;
        public final int total;
        public final int index;
        public final byte[] data;

        Fragment(byte[] fingerprint, int total, int index, byte[] data) {
            this.fingerprint = fingerprint; this.total = total; this.index = index; this.data = data;
        }
    }

    /** True if the QR content is a multi-QR fragment (vs a standalone 0x03 payload). */
    public static boolean isFragment(byte[] qrBytes) {
        return qrBytes != null && qrBytes.length >= 1 && (qrBytes[0] & 0xFF) == FRAGMENT_MAGIC;
    }

    public static Fragment parse(byte[] env) {
        if (env == null || env.length < HEADER_LEN) throw new ScDataException("Fragment shorter than envelope header");
        if ((env[0] & 0xFF) != FRAGMENT_MAGIC) {
            throw new ScDataException("Not a multi-QR fragment (magic 0x" + Integer.toHexString(env[0] & 0xFF) + ")");
        }
        int ver = env[1] & 0xFF;
        if (ver != ENVELOPE_VERSION) throw new ScDataException("Unsupported multi-QR envelope version " + ver);
        byte[] fp = Arrays.copyOfRange(env, 2, 2 + FINGERPRINT_LEN);
        int total = env[6] & 0xFF;
        int index = env[7] & 0xFF;
        int len = ((env[8] & 0xFF) << 8) | (env[9] & 0xFF);
        if (total < 1) throw new ScDataException("Fragment declares 0 total fragments");
        if (index < 1 || index > total) throw new ScDataException("Fragment index " + index + " out of range 1.." + total);
        if (HEADER_LEN + len > env.length) throw new ScDataException("Fragment data length " + len + " exceeds content");
        byte[] data = Arrays.copyOfRange(env, HEADER_LEN, HEADER_LEN + len);
        return new Fragment(fp, total, index, data);
    }
}
