package com.scdataminifier.model;

import java.util.Arrays;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.ImageType;
import com.scdataminifier.util.ImageContainers;

/**
 * Structured IMAGE value: a one-byte image header followed by the image data.
 *
 * Image header byte (bit 1 = LSB):
 *   bits 1-4: image type (0: CODEC_A, 1: CODEC_B)
 *   bit 5:    0 = container stripped, 1 = container header present
 *   bits 6-8: version - only version 1 is defined (0 and 2-7 are reserved)
 *
 * Version 1 stores no container at all; the shell is rebuilt in code:
 *   WebP: data is the raw VP8 bitstream (dimensions live in the VP8 frame
 *         header); the 20-byte RIFF + "VP8 " chunk wrapper is recomputed.
 *   CODEC_B: data is [width u16][height u16][av1cLen u8][av1C config] + AV1
 *         payload; the ~275-byte ISO-BMFF shell is rebuilt from a template.
 *
 * Stripping verifies that the rebuild is byte-identical to the original
 * encoder output and throws otherwise, so writer and viewer must run the
 * same pinned encoder/decoder versions (see NativeImageCodec pinning).
 */
public class ScImage {

    public static final int VERSION = 1;

    private final ImageType type;
    private final boolean headerPresent;
    private final int version;
    private final byte[] data; // full file when headerPresent, else v1 headerless form

    public ScImage(ImageType type, boolean headerPresent, int version, byte[] data) {
        if (type == null || data == null) throw new ScDataException("Image type/data is null");
        if (version != VERSION) {
            throw new ScDataException("Unsupported image version " + version + " (only version " + VERSION + " is defined)");
        }
        this.type = type;
        this.headerPresent = headerPresent;
        this.version = version;
        this.data = data;
    }

    /**
     * Wraps complete CODEC_A/CODEC_B file bytes, stripping the whole container
     * shell when keepHeader is false (version 1 semantics).
     */
    public static ScImage fromImageBytes(ImageType type, boolean keepHeader, byte[] imageBytes) {
        byte[] data = keepHeader ? imageBytes : stripContainer(type, imageBytes);
        return new ScImage(type, keepHeader, VERSION, data);
    }

    /** Parses an IMAGE tlv/cell/variable value (header byte + data). */
    public static ScImage parse(byte[] value) {
        if (value == null || value.length < 1) throw new ScDataException("Image value is empty");
        int b = value[0] & 0xFF;
        ImageType type = ImageType.fromCode(b & 0x0F);
        boolean headerPresent = (b & 0x10) != 0;
        int version = (b >> 5) & 0x07;
        return new ScImage(type, headerPresent, version, Arrays.copyOfRange(value, 1, value.length));
    }

    /** Encodes to the IMAGE value wire form (header byte + data). */
    public byte[] encode() {
        byte[] out = new byte[1 + data.length];
        out[0] = (byte) (type.getCode() | (headerPresent ? 0x10 : 0x00) | (version << 5));
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    /** Complete standard image file bytes (container rebuilt if stripped). */
    public byte[] toImageBytes() {
        if (headerPresent) return data;
        return type == ImageType.CODEC_A
                ? ImageContainers.buildV1A(data)
                : ImageContainers.buildV1B(data);
    }

    private static byte[] stripContainer(ImageType type, byte[] imageBytes) {
        return type == ImageType.CODEC_A
                ? ImageContainers.stripV1A(imageBytes)
                : ImageContainers.stripV1B(imageBytes);
    }

    public ImageType getType() { return type; }

    public boolean isHeaderPresent() { return headerPresent; }

    public int getVersion() { return version; }

    /** Data exactly as stored on the wire (headerless when isHeaderPresent() is false). */
    public byte[] getData() { return data; }

    /** Size of the encoded wire value (header byte + data). */
    public int getEncodedSize() { return 1 + data.length; }
}
