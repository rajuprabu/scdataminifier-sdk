package com.scdataminifier.qr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;

import javax.imageio.ImageIO;

import com.scdataminifier.ScDataException;
import com.scdataminifier.qr.QrCode.QrSegment;

/**
 * Renders an SCDataMinifier payload (or any byte[]) into a QR Code PNG.
 *
 * Two data formats:
 *  - NUMERIC: the bytes are converted to their optimal decimal representation
 *    (whole-array base-10, ~2.408 digits/byte) and encoded in QR numeric mode.
 *    Digits-only, so it survives any scanner or text channel.
 *  - BINARY:  the bytes go straight into QR byte mode (8 bits/byte, most
 *    compact). Use when your own reader can return raw bytes.
 *
 * <pre>
 * QrResult qr = SCQrGenerator.generate(payload, DataFormat.BINARY, 10, 8);
 * Files.write(Paths.get("code.png"), qr.pngBytes);
 * // qr.pixelDimension = image width == height in pixels
 * </pre>
 */
public final class SCQrGenerator {

    public enum DataFormat { NUMERIC, BINARY }

    /** Result of QR generation. */
    public static final class QrResult {
        /** Encoded PNG image bytes. */
        public final byte[] pngBytes;
        /** Width == height of the PNG in pixels (module area + border on both sides). */
        public final int pixelDimension;
        /** QR version actually used. */
        public final int version;
        /** Modules per side (before border). */
        public final int moduleCount;
        /** Border width in modules, or -1 when the border was set in pixels and is not a whole number of modules. */
        public final int quietZoneModules;
        /** Border (quiet-zone) width in pixels on each side. */
        public final int borderPixels;
        /** Pixels per module ("dot"). */
        public final int pixelsPerDot;

        QrResult(byte[] png, int dim, int version, int modules, int quietZoneModules, int borderPixels, int ppd) {
            this.pngBytes = png; this.pixelDimension = dim; this.version = version;
            this.moduleCount = modules; this.quietZoneModules = quietZoneModules;
            this.borderPixels = borderPixels; this.pixelsPerDot = ppd;
        }
    }

    /** QR-spec recommended (and hardware-scanner minimum) quiet zone in modules. */
    public static final int DEFAULT_QUIET_ZONE = 4;

    private SCQrGenerator() {}

    /** Recommended minimum border in pixels for standard 2D scanners (a 4-module quiet zone). */
    public static int minBorderPixels(int pixelsPerDot) { return DEFAULT_QUIET_ZONE * pixelsPerDot; }

    /** Generate with default ECC (MEDIUM) and the spec-recommended 4-module quiet zone. */
    public static QrResult generate(byte[] data, DataFormat format, int version, int pixelsPerDot) {
        return generate(data, format, version, pixelsPerDot, Ecc.MEDIUM, DEFAULT_QUIET_ZONE);
    }

    /** Generate with an explicit ECC level and the spec-recommended 4-module quiet zone. */
    public static QrResult generate(byte[] data, DataFormat format, int version, int pixelsPerDot, Ecc ecc) {
        return generate(data, format, version, pixelsPerDot, ecc, DEFAULT_QUIET_ZONE);
    }

    /**
     * Border specified as a whole number of QR modules.
     *
     * @param version       QR version 1-40 (fixed; throws if data does not fit)
     * @param pixelsPerDot  pixels per module in the PNG (>=1)
     * @param ecc           error-correction level
     * @param quietZone     border width in modules on each side (>=0; QR spec / hardware scanners need >=4)
     */
    public static QrResult generate(byte[] data, DataFormat format, int version, int pixelsPerDot,
                                    Ecc ecc, int quietZone) {
        if (pixelsPerDot < 1) throw new ScDataException("pixelsPerDot must be >= 1");
        if (quietZone < 0) throw new ScDataException("quietZone must be >= 0");
        return render(data, format, version, pixelsPerDot, ecc, quietZone, quietZone * pixelsPerDot);
    }

    /**
     * Border specified directly in pixels. Use when the output must hit an
     * exact size or a scanner integration mandates a specific margin.
     *
     * For reliable reading on standard 2D hardware scanners the border should be
     * at least a 4-module quiet zone, i.e. {@code minBorderPixels(pixelsPerDot)}
     * = 4 * pixelsPerDot pixels. Smaller values are allowed but may not scan.
     *
     * @param borderPixels  quiet-zone width in pixels on each side (>=0)
     */
    public static QrResult generateWithPixelBorder(byte[] data, DataFormat format, int version,
                                                   int pixelsPerDot, Ecc ecc, int borderPixels) {
        if (pixelsPerDot < 1) throw new ScDataException("pixelsPerDot must be >= 1");
        if (borderPixels < 0) throw new ScDataException("borderPixels must be >= 0");
        int quietModules = (borderPixels % pixelsPerDot == 0) ? borderPixels / pixelsPerDot : -1;
        return render(data, format, version, pixelsPerDot, ecc, quietModules, borderPixels);
    }

    private static QrResult render(byte[] data, DataFormat format, int version, int pixelsPerDot,
                                   Ecc ecc, int quietZoneModules, int borderPixels) {
        if (data == null) throw new ScDataException("data is null");

        QrSegment segment = (format == DataFormat.NUMERIC)
                ? QrSegment.makeNumeric(bytesToNumeric(data))
                : QrSegment.makeBytes(data);

        QrCode qr = QrCode.encode(Collections.singletonList(segment), ecc, version);

        // module area is size*pixelsPerDot; border is an absolute pixel margin on each side
        int dim = qr.size * pixelsPerDot + borderPixels * 2;
        BufferedImage img = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[dim];
        for (int y = 0; y < dim; y++) {
            boolean yBorder = y < borderPixels || y >= dim - borderPixels;
            int my = (y - borderPixels) / pixelsPerDot;
            for (int x = 0; x < dim; x++) {
                boolean dark;
                if (yBorder || x < borderPixels || x >= dim - borderPixels) {
                    dark = false; // quiet zone
                } else {
                    dark = qr.getModule((x - borderPixels) / pixelsPerDot, my);
                }
                row[x] = dark ? 0x000000 : 0xFFFFFF;
            }
            img.setRGB(0, y, dim, 1, row, 0, dim);
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", bos)) throw new ScDataException("No PNG writer available");
            return new QrResult(bos.toByteArray(), dim, qr.version, qr.size, quietZoneModules, borderPixels, pixelsPerDot);
        } catch (IOException e) {
            throw new ScDataException("Failed to encode PNG", e);
        }
    }

    // ==================== numeric <-> bytes ====================

    /**
     * Optimal byte[] -> decimal string for QR numeric mode (~2.408 digits/byte).
     * A 0x01 sentinel is prepended so leading 0x00 bytes survive the round trip.
     */
    public static String bytesToNumeric(byte[] data) {
        byte[] tagged = new byte[data.length + 1];
        tagged[0] = 0x01;
        System.arraycopy(data, 0, tagged, 1, data.length);
        return new BigInteger(1, tagged).toString();
    }

    /** Inverse of bytesToNumeric. */
    public static byte[] numericToBytes(String digits) {
        BigInteger n = new BigInteger(digits);
        byte[] full = n.toByteArray(); // big-endian, may have a leading 0x00 sign byte
        int start = (full.length > 0 && full[0] == 0) ? 1 : 0;
        if (start >= full.length || (full[start] & 0xFF) != 0x01) {
            throw new ScDataException("Not a valid SCQrGenerator numeric string (sentinel missing)");
        }
        byte[] out = new byte[full.length - start - 1];
        System.arraycopy(full, start + 1, out, 0, out.length);
        return out;
    }

    /** Maximum payload bytes encodable at the given format, version and ECC. */
    public static int maxPayloadBytes(DataFormat format, int version, Ecc ecc) {
        if (version < 1 || version > 40) throw new ScDataException("QR version must be 1-40, got " + version);
        int capacityBits = QrCode.getNumDataCodewords(version, ecc) * 8;
        int lo = 0, hi = capacityBits / 8 + 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (fits(mid, format, version, ecc, capacityBits)) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private static boolean fits(int numBytes, DataFormat format, int version, Ecc ecc, int capacityBits) {
        byte[] probe = new byte[numBytes];
        if (format == DataFormat.NUMERIC) java.util.Arrays.fill(probe, (byte) 0xFF); // worst-case digit count
        QrSegment seg = (format == DataFormat.NUMERIC)
                ? QrSegment.makeNumeric(bytesToNumeric(probe))
                : QrSegment.makeBytes(probe);
        int ccbits = seg.mode.numCharCountBits(version);
        if (seg.numChars >= (1 << ccbits)) return false;
        return 4 + ccbits + seg.bitLength <= capacityBits;
    }

    /** Smallest version (1-40) that fits the data for the given format and ECC, or -1 if none. */
    public static int minimumVersion(byte[] data, DataFormat format, Ecc ecc) {
        QrSegment segment = (format == DataFormat.NUMERIC)
                ? QrSegment.makeNumeric(bytesToNumeric(data))
                : QrSegment.makeBytes(data);
        for (int v = 1; v <= 40; v++) {
            int ccbits = segment.mode.numCharCountBits(v);
            if (segment.numChars >= (1 << ccbits)) continue;
            int used = 4 + ccbits + segment.bitLength;
            if (used <= QrCode.getNumDataCodewords(v, ecc) * 8) return v;
        }
        return -1;
    }
}
