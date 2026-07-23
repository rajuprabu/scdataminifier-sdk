package com.scdataminifier.util;

import java.util.Arrays;

import com.scdataminifier.ScDataException;

/**
 * Version-1 image container handling: instead of stripping just the fixed
 * file prefix (version 0), the whole container shell is dropped and
 * reconstructed in code from a few dynamic fields kept in the payload.
 *
 * WebP v1: stored data is the raw VP8 bitstream (dimensions live inside the
 * VP8 frame header). Rebuild = 12-byte RIFF header + 8-byte "VP8 " chunk
 * header + bitstream. Saves 20 bytes; only simple lossy (VP8) files qualify.
 *
 * CODEC_B v1: stored data is [width u16][height u16][av1cLen u8][av1C config]
 * followed by the raw AV1 payload. The ~275-byte ISO-BMFF shell
 * (ftyp/meta/mdat) is rebuilt from a template mirroring libavif's output.
 * Saves ~266 bytes per image.
 *
 * Safety rule: strip methods verify that rebuilding reproduces the original
 * file byte-for-byte and throw otherwise, so a stripped image can always be
 * reconstructed exactly.
 */
public final class ImageContainers {

    private ImageContainers() {}

    // ==================== WebP v1 ====================

    public static byte[] stripV1A(byte[] file) {
        if (file.length < 21
                || file[0] != 'R' || file[1] != 'I' || file[2] != 'F' || file[3] != 'F'
                || file[8] != 'W' || file[9] != 'E' || file[10] != 'B' || file[11] != 'P') {
            throw new ScDataException("Not a type-A container");
        }
        if (!(file[12] == 'V' && file[13] == 'P' && file[14] == '8' && file[15] == ' ')) {
            throw new ScDataException("Only the simple type-A subchunk supports v1 stripping; "
                    + "this file uses " + new String(file, 12, 4) + " - use version 0 or headerPresent=true");
        }
        int chunkLen = (file[16] & 0xFF) | ((file[17] & 0xFF) << 8) | ((file[18] & 0xFF) << 16) | ((file[19] & 0xFF) << 24);
        if (20 + chunkLen > file.length) throw new ScDataException("Corrupt type-A chunk length");
        byte[] payload = Arrays.copyOfRange(file, 20, 20 + chunkLen);
        if (!Arrays.equals(buildV1A(payload), file)) {
            throw new ScDataException("Type-A v1 reconstruction mismatch (extra chunks?); use version 0 or headerPresent=true");
        }
        return payload;
    }

    public static byte[] buildV1A(byte[] payload) {
        int pad = payload.length & 1; // RIFF chunks are even-padded
        ByteWriter w = new ByteWriter();
        w.writeByte('R').writeByte('I').writeByte('F').writeByte('F');
        writeLe32(w, 4 + 8 + payload.length + pad);
        w.writeByte('W').writeByte('E').writeByte('B').writeByte('P');
        w.writeByte('V').writeByte('P').writeByte('8').writeByte(' ');
        writeLe32(w, payload.length);
        w.writeBytes(payload);
        if (pad == 1) w.writeByte(0);
        return w.toBytes();
    }

    private static void writeLe32(ByteWriter w, int v) {
        w.writeByte(v).writeByte(v >>> 8).writeByte(v >>> 16).writeByte(v >>> 24);
    }

    // ==================== CODEC_B v1 ====================

    // Fixed boxes exactly as produced by libavif's avifenc for a single
    // 8-bit 4:2:0 (AV1 Main profile, brand MA1B) color image with sRGB nclx.
    private static final byte[] FTYP = hex("00000020667479706176696600000000617669666d6966316d6961664d413142");
    private static final byte[] HDLR = hex("0000002168646c7200000000000000007069637400000000000000000000000000");
    private static final byte[] PITM = hex("0000000e7069746d000000000001");
    private static final byte[] IINF = hex("0000002869696e660000000000010000001a696e6665020000000001000061763031436f6c6f7200");
    private static final byte[] PIXI = hex("00000010706978690000000003080808");
    private static final byte[] COLR = hex("00000013636f6c726e636c780001000d000680");
    private static final byte[] IPMA = hex("0000001769706d61000000000000000100010401028304");

    /** @return [width u16][height u16][av1cLen u8][av1C payload] + AV1 data */
    public static byte[] stripV1B(byte[] file) {
        // locate ispe / av1C inside meta>iprp>ipco, and the extent via iloc
        int metaOff = findBox(file, 0, file.length, "meta");
        int metaEnd = metaOff + u32(file, metaOff);
        int iprpOff = findBox(file, metaOff + 12, metaEnd, "iprp");
        int iprpEnd = iprpOff + u32(file, iprpOff);
        int ipcoOff = findBox(file, iprpOff + 8, iprpEnd, "ipco");
        int ipcoEnd = ipcoOff + u32(file, ipcoOff);
        int ispeOff = findBox(file, ipcoOff + 8, ipcoEnd, "ispe");
        int av1cOff = findBox(file, ipcoOff + 8, ipcoEnd, "av1C");
        int width = u32(file, ispeOff + 12);
        int height = u32(file, ispeOff + 16);
        int av1cLen = u32(file, av1cOff) - 8;
        if (width > 0xFFFF || height > 0xFFFF) throw new ScDataException("Image dimensions exceed 65535");
        if (av1cLen < 1 || av1cLen > 0xFF) throw new ScDataException("Unsupported av1C size: " + av1cLen);

        int ilocOff = findBox(file, metaOff + 12, metaEnd, "iloc");
        int extentOffset = u32(file, ilocOff + 22);
        int extentLength = u32(file, ilocOff + 26);
        if (extentOffset + extentLength > file.length) throw new ScDataException("Corrupt CODEC_B iloc extent");

        ByteWriter w = new ByteWriter();
        w.writeShort(width).writeShort(height).writeByte(av1cLen);
        w.writeBytes(Arrays.copyOfRange(file, av1cOff + 8, av1cOff + 8 + av1cLen));
        w.writeBytes(Arrays.copyOfRange(file, extentOffset, extentOffset + extentLength));
        byte[] data = w.toBytes();

        if (!Arrays.equals(buildV1B(data), file)) {
            throw new ScDataException("CODEC_B v1 reconstruction mismatch (non-canonical container); "
                    + "use version 0 or headerPresent=true");
        }
        return data;
    }

    public static byte[] buildV1B(byte[] data) {
        ByteReader r = new ByteReader(data);
        int width = r.readShort();
        int height = r.readShort();
        int av1cLen = r.readByte();
        byte[] av1c = r.readBytes(av1cLen);
        byte[] payload = r.readBytes(r.remaining());

        byte[] ispe = boxCat("ispe", hex("00000000"), u32be(width), u32be(height));
        byte[] av1C = boxCat("av1C", av1c);
        byte[] ipco = boxCat("ipco", ispe, PIXI, av1C, COLR);
        byte[] iprp = boxCat("iprp", ipco, IPMA);

        // meta = fullbox(hdlr pitm iloc iinf iprp); iloc extent offset = full shell size
        int metaSize = 12 + HDLR.length + PITM.length + 30 + IINF.length + iprp.length;
        int shellSize = FTYP.length + metaSize + 8; // + mdat header
        // iloc fixed part (22 bytes): size type ver/flags, offSize/lenSize=4/4,
        // baseOffSize=0, itemCount=1, itemID=1, dataRef=0, extentCount=1;
        // then extent offset (u32) + extent length (u32) are appended below.
        byte[] ilocFixed = hex("0000001e696c6f630000000044000001000100000001");
        ByteWriter out = new ByteWriter();
        out.writeBytes(FTYP);
        out.writeBytes(u32be(metaSize));
        out.writeByte('m').writeByte('e').writeByte('t').writeByte('a');
        out.writeBytes(hex("00000000"));
        out.writeBytes(HDLR);
        out.writeBytes(PITM);
        out.writeBytes(ilocFixed);
        out.writeBytes(u32be(shellSize));
        out.writeBytes(u32be(payload.length));
        out.writeBytes(IINF);
        out.writeBytes(iprp);
        out.writeBytes(u32be(8 + payload.length));
        out.writeByte('m').writeByte('d').writeByte('a').writeByte('t');
        out.writeBytes(payload);
        return out.toBytes();
    }

    // ==================== helpers ====================

    private static int findBox(byte[] buf, int from, int end, String type) {
        int off = from;
        while (off + 8 <= end) {
            int size = u32(buf, off);
            if (size < 8) break;
            if (buf[off + 4] == type.charAt(0) && buf[off + 5] == type.charAt(1)
                    && buf[off + 6] == type.charAt(2) && buf[off + 7] == type.charAt(3)) {
                return off;
            }
            off += size;
        }
        throw new ScDataException("CODEC_B box '" + type + "' not found");
    }

    private static int u32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] u32be(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    private static byte[] boxCat(String type, byte[]... parts) {
        int len = 8;
        for (byte[] p : parts) len += p.length;
        ByteWriter w = new ByteWriter();
        w.writeBytes(u32be(len));
        for (int i = 0; i < 4; i++) w.writeByte(type.charAt(i));
        for (byte[] p : parts) w.writeBytes(p);
        return w.toBytes();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
