package com.scdataminifier.qr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.scdataminifier.ScDataException;

/**
 * Minimal, dependency-free QR Code encoder (ISO/IEC 18004) supporting numeric
 * and byte segments, all versions 1-40 and all four error-correction levels.
 *
 * Algorithm follows the well-known public-domain reference design (Reed-Solomon
 * over GF(256), function-pattern layout, penalty-based mask selection). Only
 * encoding is implemented - decoding is the scanner's job.
 */
public final class QrCode {

    public final int version;
    public final int size;
    public final Ecc errorCorrectionLevel;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    // ==================== public entry point ====================

    /** Encodes the segments at exactly the given version (throws if they don't fit). */
    public static QrCode encode(List<QrSegment> segments, Ecc ecl, int version) {
        Objects.requireNonNull(segments);
        Objects.requireNonNull(ecl);
        if (version < 1 || version > 40) throw new ScDataException("QR version must be 1-40, got " + version);

        int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
        int usedBits = 0;
        for (QrSegment seg : segments) {
            int ccbits = seg.mode.numCharCountBits(version);
            if (seg.numChars >= (1 << ccbits)) throw new ScDataException("Segment too long for version " + version);
            usedBits += 4 + ccbits + seg.bitLength;
        }
        if (usedBits > dataCapacityBits) {
            throw new ScDataException("Data (" + usedBits + " bits) does not fit in QR version " + version
                    + " ecc " + ecl + " (capacity " + dataCapacityBits + " bits); use a higher version or lower ECC");
        }

        BitBuffer bb = new BitBuffer();
        for (QrSegment seg : segments) {
            bb.appendBits(seg.mode.modeBits, 4);
            bb.appendBits(seg.numChars, seg.mode.numCharCountBits(version));
            bb.appendData(seg);
        }
        // terminator + byte-align + pad bytes 0xEC/0x11
        bb.appendBits(0, Math.min(4, dataCapacityBits - bb.bitLength));
        bb.appendBits(0, (8 - bb.bitLength % 8) % 8);
        for (int padByte = 0xEC; bb.bitLength < dataCapacityBits; padByte ^= 0xEC ^ 0x11) {
            bb.appendBits(padByte, 8);
        }

        byte[] dataCodewords = new byte[bb.bitLength / 8];
        for (int i = 0; i < bb.bitLength; i++) {
            if (bb.getBit(i)) dataCodewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        return new QrCode(version, ecl, dataCodewords);
    }

    // ==================== construction ====================

    private QrCode(int version, Ecc ecl, byte[] dataCodewords) {
        this.version = version;
        this.errorCorrectionLevel = ecl;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];

        drawFunctionPatterns();
        byte[] allCodewords = addEccAndInterleave(dataCodewords);
        drawCodewords(allCodewords);

        int minPenalty = Integer.MAX_VALUE, bestMask = 0;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormatBits(mask);
            int penalty = getPenaltyScore();
            if (penalty < minPenalty) { minPenalty = penalty; bestMask = mask; }
            applyMask(mask); // XOR is its own inverse - undo
        }
        applyMask(bestMask);
        drawFormatBits(bestMask);
    }

    /** @return true if the module is dark; false if light or out of bounds. */
    public boolean getModule(int x, int y) {
        return 0 <= x && x < size && 0 <= y && y < size && modules[y][x];
    }

    // ==================== function patterns ====================

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0);
            setFunctionModule(i, 6, i % 2 == 0);
        }
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        int[] alignPatPos = getAlignmentPatternPositions();
        int n = alignPatPos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                    drawAlignmentPattern(alignPatPos[i], alignPatPos[j]);
                }
            }
        }
        drawFormatBits(0);
        drawVersion();
    }

    private void drawFormatBits(int mask) {
        int data = errorCorrectionLevel.formatBits << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunctionModule(8, i, getBit(bits, i));
        setFunctionModule(8, 7, getBit(bits, 6));
        setFunctionModule(8, 8, getBit(bits, 7));
        setFunctionModule(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) setFunctionModule(14 - i, 8, getBit(bits, i));

        for (int i = 0; i < 8; i++) setFunctionModule(size - 1 - i, 8, getBit(bits, i));
        for (int i = 8; i < 15; i++) setFunctionModule(8, size - 15 + i, getBit(bits, i));
        setFunctionModule(8, size - 8, true); // always-dark module
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3, b = i / 3;
            setFunctionModule(a, b, bit);
            setFunctionModule(b, a, bit);
        }
    }

    private void drawFinderPattern(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignmentPattern(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void setFunctionModule(int x, int y, boolean isDark) {
        modules[y][x] = isDark;
        isFunction[y][x] = true;
    }

    // ==================== error correction ====================

    private byte[] addEccAndInterleave(byte[] data) {
        int ver = version;
        Ecc ecl = errorCorrectionLevel;
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][ver];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][ver];
        int rawCodewords = getNumRawDataModules(ver) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, dat.length);
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
            blocks[i] = block;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[k++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    // ==================== masking ====================

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    case 7: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    default: throw new ScDataException("Bad mask");
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    private int getPenaltyScore() {
        int result = 0;
        for (int y = 0; y < size; y++) {
            boolean runColor = false; int runX = 0; int[] hist = new int[7];
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    runX++;
                    if (runX == 5) result += 3; else if (runX > 5) result++;
                } else {
                    finderPenaltyAddHistory(runX, hist);
                    if (!runColor) result += finderPenaltyCountPatterns(hist) * 40;
                    runColor = modules[y][x]; runX = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runX, hist) * 40;
        }
        for (int x = 0; x < size; x++) {
            boolean runColor = false; int runY = 0; int[] hist = new int[7];
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    runY++;
                    if (runY == 5) result += 3; else if (runY > 5) result++;
                } else {
                    finderPenaltyAddHistory(runY, hist);
                    if (!runColor) result += finderPenaltyCountPatterns(hist) * 40;
                    runColor = modules[y][x]; runY = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runY, hist) * 40;
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : modules) for (boolean c : row) if (c) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += k * 10;
        return result;
    }

    private int finderPenaltyCountPatterns(int[] runHistory) {
        int n = runHistory[1];
        boolean core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 && runHistory[4] == n && runHistory[5] == n;
        return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0)
             + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0);
    }

    private int finderPenaltyTerminateAndCount(boolean currentRunColor, int currentRunLength, int[] runHistory) {
        if (currentRunColor) {
            finderPenaltyAddHistory(currentRunLength, runHistory);
            currentRunLength = 0;
        }
        currentRunLength += size;
        finderPenaltyAddHistory(currentRunLength, runHistory);
        return finderPenaltyCountPatterns(runHistory);
    }

    private void finderPenaltyAddHistory(int currentRunLength, int[] runHistory) {
        if (runHistory[0] == 0) currentRunLength += size; // add light border to first run
        System.arraycopy(runHistory, 0, runHistory, 1, runHistory.length - 1);
        runHistory[0] = currentRunLength;
    }

    // ==================== helpers & tables ====================

    private int[] getAlignmentPatternPositions() {
        if (version == 1) return new int[] {};
        int numAlign = version / 7 + 2;
        int step = (version == 32) ? 26 : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private static int getNumRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    static int getNumDataCodewords(int ver, Ecc ecl) {
        return getNumRawDataModules(ver) / 8
                - ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][ver];
    }

    private static byte[] reedSolomonComputeDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) reedSolomonMultiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int reedSolomonMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static boolean getBit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
        {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}, // Low
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28}, // Medium
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}, // Quartile
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}, // High
    };

    private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25}, // Low
        {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49}, // Medium
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68}, // Quartile
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81}, // High
    };

    // ==================== segment + bit buffer ====================

    /** A numeric or byte data segment. */
    public static final class QrSegment {
        public enum Mode {
            NUMERIC(0x1, 10, 12, 14),
            BYTE(0x4, 8, 16, 16);
            final int modeBits;
            private final int[] ccBits;
            Mode(int modeBits, int a, int b, int c) { this.modeBits = modeBits; this.ccBits = new int[] { a, b, c }; }
            int numCharCountBits(int ver) { return ccBits[(ver + 7) / 17]; }
        }

        final Mode mode;
        final int numChars;
        final boolean[] data; // one boolean per bit
        final int bitLength;

        private QrSegment(Mode mode, int numChars, boolean[] data) {
            this.mode = mode; this.numChars = numChars; this.data = data; this.bitLength = data.length;
        }

        /** Byte-mode segment carrying arbitrary binary. */
        public static QrSegment makeBytes(byte[] bytes) {
            boolean[] bits = new boolean[bytes.length * 8];
            for (int i = 0; i < bytes.length; i++) {
                for (int j = 0; j < 8; j++) bits[i * 8 + j] = ((bytes[i] >>> (7 - j)) & 1) != 0;
            }
            return new QrSegment(Mode.BYTE, bytes.length, bits);
        }

        /** Numeric-mode segment; input must be decimal digits only. */
        public static QrSegment makeNumeric(String digits) {
            List<Boolean> bits = new ArrayList<>();
            for (int i = 0; i < digits.length(); ) {
                int n = Math.min(3, digits.length() - i);
                String chunk = digits.substring(i, i + n);
                for (int c = 0; c < chunk.length(); c++) {
                    if (chunk.charAt(c) < '0' || chunk.charAt(c) > '9') {
                        throw new ScDataException("Numeric segment must be digits only");
                    }
                }
                int val = Integer.parseInt(chunk);
                int bitLen = n * 3 + 1; // 3->10, 2->7, 1->4
                for (int b = bitLen - 1; b >= 0; b--) bits.add(((val >>> b) & 1) != 0);
                i += n;
            }
            boolean[] arr = new boolean[bits.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = bits.get(i);
            return new QrSegment(Mode.NUMERIC, digits.length(), arr);
        }
    }

    private static final class BitBuffer {
        private boolean[] bits = new boolean[64];
        int bitLength = 0;

        void appendBits(int val, int len) {
            if (len < 0 || len > 31) throw new ScDataException("Bad bit length");
            ensure(len);
            for (int i = len - 1; i >= 0; i--) bits[bitLength++] = ((val >>> i) & 1) != 0;
        }

        void appendData(QrSegment seg) {
            ensure(seg.bitLength);
            for (int i = 0; i < seg.bitLength; i++) bits[bitLength++] = seg.data[i];
        }

        boolean getBit(int i) { return bits[i]; }

        private void ensure(int extra) {
            if (bitLength + extra > bits.length) {
                int n = bits.length * 2;
                while (n < bitLength + extra) n *= 2;
                boolean[] grown = new boolean[n];
                System.arraycopy(bits, 0, grown, 0, bitLength);
                bits = grown;
            }
        }
    }
}
