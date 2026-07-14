package com.scdataminifier.qr;

import java.util.ArrayList;
import java.util.List;

import com.scdataminifier.ScDataException;
import com.scdataminifier.qr.SCQrGenerator.DataFormat;
import com.scdataminifier.qr.SCQrGenerator.QrResult;

/**
 * Splits a large SCDataMinifier payload across multiple QR codes (see
 * {@link MultiQr}). When the payload fits one QR it is emitted as a single
 * standalone code (magic 0x03, no envelope). Otherwise it is chunked into
 * fragments (magic 0x05) that {@link MultiQrAssembler} reassembles in any
 * scan order.
 *
 * All fragments use the same QR version, so every image has identical
 * dimensions regardless of how full the last fragment is.
 *
 * <pre>
 * List&lt;QrResult&gt; codes = MultiQrWriter.split(payload, DataFormat.NUMERIC, 10, 8);
 * for (int i = 0; i &lt; codes.size(); i++)
 *     Files.write(Paths.get("code-" + (i+1) + ".png"), codes.get(i).pngBytes);
 * </pre>
 */
public final class MultiQrWriter {

    private MultiQrWriter() {}

    /** Split with default ECC (MEDIUM) and a 4-module quiet zone (in pixels). */
    public static List<QrResult> split(byte[] payload, DataFormat format, int version, int pixelsPerDot) {
        return split(payload, format, version, pixelsPerDot, Ecc.MEDIUM,
                SCQrGenerator.minBorderPixels(pixelsPerDot));
    }

    /**
     * @param payload       the complete (built/signed/encrypted) payload bytes
     * @param format        NUMERIC (digits, scanner-safe) or BINARY (byte mode)
     * @param version       QR version 1-40 used for every fragment
     * @param pixelsPerDot  pixels per module
     * @param ecc           error-correction level
     * @param borderPixels  quiet-zone width in pixels on each side
     * @return one QrResult per QR code (size 1 when the payload fits a single code)
     */
    public static List<QrResult> split(byte[] payload, DataFormat format, int version,
                                       int pixelsPerDot, Ecc ecc, int borderPixels) {
        if (payload == null || payload.length == 0) throw new ScDataException("payload is empty");

        int capacity = SCQrGenerator.maxPayloadBytes(format, version, ecc);

        // Single-QR case: the payload rides alone as a standalone 0x03 code.
        if (payload.length <= capacity) {
            List<QrResult> one = new ArrayList<QrResult>(1);
            one.add(SCQrGenerator.generateWithPixelBorder(payload, format, version, pixelsPerDot, ecc, borderPixels));
            return one;
        }

        int usable = capacity - MultiQr.HEADER_LEN;
        if (usable < 1) {
            throw new ScDataException("QR version " + version + " (" + format
                    + ", capacity " + capacity + " bytes) is too small to carry the "
                    + MultiQr.HEADER_LEN + "-byte fragment header");
        }
        int n = (payload.length + usable - 1) / usable;
        if (n > MultiQr.MAX_FRAGMENTS) {
            throw new ScDataException("Payload of " + payload.length + " bytes needs " + n
                    + " fragments (>" + MultiQr.MAX_FRAGMENTS + ") at version " + version
                    + "; use a higher QR version or lower ECC");
        }

        byte[] fingerprint = MultiQr.fingerprint(payload);
        List<QrResult> out = new ArrayList<QrResult>(n);
        int off = 0;
        for (int i = 1; i <= n; i++) {
            int len = Math.min(usable, payload.length - off);
            byte[] envelope = MultiQr.wrap(fingerprint, n, i, payload, off, len);
            out.add(SCQrGenerator.generateWithPixelBorder(envelope, format, version, pixelsPerDot, ecc, borderPixels));
            off += len;
        }
        return out;
    }

    /** Number of QR codes the payload would split into at the given settings (1 if it fits one). */
    public static int fragmentCount(byte[] payload, DataFormat format, int version, Ecc ecc) {
        if (payload == null || payload.length == 0) throw new ScDataException("payload is empty");
        int capacity = SCQrGenerator.maxPayloadBytes(format, version, ecc);
        if (payload.length <= capacity) return 1;
        int usable = capacity - MultiQr.HEADER_LEN;
        if (usable < 1) throw new ScDataException("QR version too small for a fragment header");
        return (payload.length + usable - 1) / usable;
    }
}
