package com.scdataminifier.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.ImageType;
import com.scdataminifier.model.ScImage;

/**
 * Compresses images to WEBP/AVIF at a target wire size.
 *
 * <pre>
 * ScImage img = ScImageCodec.compress(pngOrJpegBytes, ImageType.WEBP,
 *                                     false,      // strip container header
 *                                     96, 96,     // output pixels
 *                                     600);       // max encoded value bytes
 * writer.startTlvContent().addImage(img).endContent();
 * </pre>
 *
 * The codec scales the source, then binary-searches the quality setting for
 * the highest quality whose encoded IMAGE value (header byte + data) fits
 * targetSizeBytes. Desktop encoding uses cwebp/avifenc; Android apps install
 * a Bitmap.compress-based encoder via {@link #setEncoder(ImageEncoder)}.
 */
public final class ScImageCodec {

    private static volatile ImageEncoder encoder = new CliImageEncoder();

    private ScImageCodec() {}

    /** Replaces the encoding backend (e.g. an Android Bitmap.compress implementation). */
    public static void setEncoder(ImageEncoder customEncoder) {
        if (customEncoder == null) throw new ScDataException("Encoder is null");
        encoder = customEncoder;
    }

    /**
     * Pins the CLI encoder tools to exact versions; every encode verifies the
     * installed cwebp/avifenc match and fails loudly on drift.
     */
    public static void pinEncoderVersions(String cwebpVersion, String avifencVersion) {
        encoder = new CliImageEncoder(cwebpVersion, avifencVersion);
    }

    /**
     * Production setup: loads the bundled native scimage library (statically
     * linked pinned libwebp/libavif) and uses it for all encoding. Decoding
     * via {@link #decode(ScImage)} also goes through it.
     */
    public static void useNativeCodec(String libraryPath) {
        NativeImageCodec.load(libraryPath);
        encoder = new NativeImageCodec();
    }

    /**
     * Production setup for a self-contained jar: loads the native scimage library that is
     * <em>bundled inside this jar</em>, automatically selecting the binary for the current
     * operating system and CPU architecture (see {@link BundledNativeLoader}), then uses it
     * for all encoding/decoding. No external path or {@code java.library.path} needed.
     *
     * @throws com.scdataminifier.ScDataException if this jar carries no native for the
     *         running platform, or the native fails to load / version-check
     */
    public static void loadBundledNative() {
        BundledNativeLoader.load();
        encoder = new NativeImageCodec();
    }

    /**
     * Production setup for a self-contained, obfuscated jar: loads the native scimage library
     * from the two split+scrambled parts bundled in this jar for the current OS/arch, merging
     * and loading it <em>in memory</em> (see {@link BundledNativeLoader#loadObfuscated()}). The
     * raw {@code .so}/{@code .dll}/{@code .dylib} is never present in the jar and never written
     * to persistent storage on Linux/macOS. This is the recommended entry for the shipped jar.
     *
     * @throws com.scdataminifier.ScDataException if this jar carries no parts for the running
     *         platform, or the native fails to reconstruct / load / version-check
     */
    public static void loadBundledObfuscatedNative() {
        BundledNativeLoader.loadObfuscated();
        encoder = new NativeImageCodec();
    }

    /** Decodes an ScImage to pixels using the native codec. */
    public static java.awt.image.BufferedImage decode(ScImage image) {
        return NativeImageCodec.decodeToImage(image);
    }

    /**
     * Production setup with an obfuscated native library: reconstructs the
     * codec in memory from two split+scrambled files (see LibraryObfuscator)
     * and uses it for all encoding. The real library never exists on disk
     * under its own name. Keyless obfuscation - see LibraryObfuscator's note.
     */
    public static void useObfuscatedNativeCodec(java.nio.file.Path blobFile, java.nio.file.Path partFile) {
        NativeLibraryLoader.loadFromFiles(blobFile, partFile);
        encoder = new NativeImageCodec();
    }

    /**
     * @param sourceImage     source image bytes in any ImageIO-readable format (JPEG, PNG, BMP, GIF)
     * @param type            output format (WEBP or AVIF)
     * @param includeHeader   true to keep the container header, false to strip it (image version 1)
     * @param widthInPixels   output width
     * @param heightInPixels  output height
     * @param targetSizeBytes maximum size of the encoded IMAGE value (header byte + data)
     * @return the best-quality image that fits targetSizeBytes
     */
    public static ScImage compress(byte[] sourceImage, ImageType type, boolean includeHeader,
                                   int widthInPixels, int heightInPixels, int targetSizeBytes) {
        if (type == null) throw new ScDataException("Image type is null");
        if (widthInPixels < 1 || heightInPixels < 1) throw new ScDataException("Invalid output dimensions");
        if (targetSizeBytes < 2) throw new ScDataException("Target size too small");

        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(sourceImage));
        } catch (IOException e) {
            throw new ScDataException("Failed to read source image", e);
        }
        if (source == null) throw new ScDataException("Source image format not recognized (expected JPEG/PNG/BMP/GIF)");

        // honour EXIF orientation from phone-camera JPEGs
        source = JpegExif.normalize(source, JpegExif.readOrientation(sourceImage));
        BufferedImage scaled = scale(source, widthInPixels, heightInPixels);

        int lo = 1, hi = 100;
        ScImage best = null;
        while (lo <= hi) {
            int quality = (lo + hi) >>> 1;
            ScImage candidate = encodeAt(scaled, type, includeHeader, quality);
            if (candidate.getEncodedSize() <= targetSizeBytes) {
                best = candidate;
                lo = quality + 1;
            } else {
                hi = quality - 1;
            }
        }
        if (best == null) {
            int minimum = encodeAt(scaled, type, includeHeader, 1).getEncodedSize();
            throw new ScDataException("Cannot fit " + widthInPixels + "x" + heightInPixels + " " + type
                    + " into " + targetSizeBytes + " bytes; minimum at quality 1 is " + minimum
                    + " bytes - reduce dimensions or raise the target");
        }
        return best;
    }

    private static ScImage encodeAt(BufferedImage image, ImageType type, boolean includeHeader, int quality) {
        byte[] file = encoder.encode(image, type, quality);
        return ScImage.fromImageBytes(type, includeHeader, file);
    }

    /** Aspect-preserving scale with center-crop ("cover") - never distorts faces. */
    private static BufferedImage scale(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height && src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        double factor = Math.max((double) width / src.getWidth(), (double) height / src.getHeight());
        int scaledW = (int) Math.round(src.getWidth() * factor);
        int scaledH = (int) Math.round(src.getHeight() * factor);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, (width - scaledW) / 2, (height - scaledH) / 2, scaledW, scaledH, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
