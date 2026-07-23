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
 * Compresses images to CODEC_A/CODEC_B at a target wire size.
 *
 * <pre>
 * ScImage img = ScImageCodec.compress(pngOrJpegBytes, ImageType.CODEC_A,
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

    // Native-only: image compression runs through the bundled native codec (statically-linked,
    // pinned libwebp/libavif/aom). There is no CLI/external-tool backend. The encoder is set by
    // loadBundledObfuscatedNative()/useNativeCodec(); compress() fails until then.
    private static volatile ImageEncoder encoder;

    private ScImageCodec() {}

    /**
     * Replaces the encoding backend — the only supported override is an on-device native
     * implementation (e.g. Android's own NDK build); there is no CLI/external-tool encoder.
     */
    public static void setEncoder(ImageEncoder customEncoder) {
        if (customEncoder == null) throw new ScDataException("Encoder is null");
        encoder = customEncoder;
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

    /**
     * Applies a signed license to the loaded native library. The native refuses all
     * encode/decode until this succeeds — see {@link NativeImageCodec#applyLicense}.
     * Call once after loading the native and before any codec use.
     *
     * @return 0 on success; negative license error code otherwise
     */
    public static int applyLicense(byte[] license, String packageName) {
        return NativeImageCodec.applyLicense(license, packageName);
    }

    /** True once a valid license has been accepted. */
    public static boolean isLicensed() {
        return NativeImageCodec.isLicensed();
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
     * @param type            output format (CODEC_A or CODEC_B)
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

        // Preferred path: the encoder's own rate-control (CODEC_A: libwebp target_size +
        // multi-pass + autofilter). At small budgets this distributes the bits far better
        // than any fixed-quality encode of the same size — visibly smoother output.
        ScImage rated = encodeAtTargetSize(scaled, type, includeHeader, targetSizeBytes);
        if (rated != null) {
            return rated;
        }

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
        ImageEncoder enc = encoder;
        if (enc == null) {
            throw new ScDataException("Native codec not loaded — call "
                    + "ScImageCodec.loadBundledObfuscatedNative() (and applyLicense) or "
                    + "useNativeCodec(path) before compressing");
        }
        byte[] file = enc.encode(image, type, quality);
        return ScImage.fromImageBytes(type, includeHeader, file);
    }

    /**
     * Encodes via the encoder's rate-control aimed at the byte budget. Returns null when the
     * encoder has no rate-control for this type (CODEC_B, custom encoders) or the bundled native
     * predates the entry point — the caller then runs the fixed-quality search instead.
     *
     * <p>The budget covers the encoded IMAGE value (header byte + data), while the encoder
     * targets complete file bytes; when stripping, the fixed 20-byte CODEC_A container shell
     * comes off again, so the file target is {@code value - 1 + 20}. libwebp's target_size is
     * a goal, not a cap — on overshoot the target is lowered by the overshoot and re-tried.</p>
     */
    private static ScImage encodeAtTargetSize(BufferedImage image, ImageType type,
                                              boolean includeHeader, int targetSizeBytes) {
        ImageEncoder enc = encoder;
        if (enc == null) {
            throw new ScDataException("Native codec not loaded — call "
                    + "ScImageCodec.loadBundledObfuscatedNative() (and applyLicense) or "
                    + "useNativeCodec(path) before compressing");
        }
        int fileTarget = targetSizeBytes - 1 + (includeHeader ? 0 : 20);
        for (int attempt = 0; attempt < 4 && fileTarget >= 64; attempt++) {
            byte[] file;
            try {
                file = enc.encodeTarget(image, type, fileTarget);
            } catch (LinkageError e) {
                return null; // bundled native for this platform predates nEncodeATarget
            }
            if (file == null) {
                return null;
            }
            ScImage candidate = ScImage.fromImageBytes(type, includeHeader, file);
            if (candidate.getEncodedSize() <= targetSizeBytes) {
                return candidate;
            }
            fileTarget -= (candidate.getEncodedSize() - targetSizeBytes) + 8;
        }
        return null;
    }

    /** Aspect-preserving scale with center-crop ("cover") - never distorts faces. */
    private static BufferedImage scale(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height && src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        double factor = Math.max((double) width / src.getWidth(), (double) height / src.getHeight());
        int scaledW = (int) Math.round(src.getWidth() * factor);
        int scaledH = (int) Math.round(src.getHeight() * factor);

        // Progressive halving before the final resize: Java's bilinear only samples a 2x2
        // neighbourhood, so a single pass at a large ratio (e.g. 1280 -> 100 px) point-samples
        // and aliases badly — the resulting pixel noise then wastes encoder bits and shows up
        // as blocky artifacts at QR-thumbnail budgets. Halving stays within bilinear's working
        // range at every step and behaves like a proper area filter.
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= scaledW && cur.getHeight() / 2 >= scaledH) {
            cur = drawScaled(cur, cur.getWidth() / 2, cur.getHeight() / 2, 0, 0,
                    cur.getWidth() / 2, cur.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }

        // Final step is BICUBIC: once the halvings have brought the image within 2x of the
        // target, bicubic's wider kernel preserves edge contrast that bilinear softens away —
        // the same "crisp at thumbnail size" character as GDI+ HighQualityBicubic, which the
        // reference C# pipeline used. (Bicubic alone at a large ratio would alias — it only
        // runs after the halvings.)
        return drawScaled(cur, width, height, (width - scaledW) / 2, (height - scaledH) / 2,
                scaledW, scaledH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage drawScaled(BufferedImage src, int canvasW, int canvasH,
                                            int x, int y, int drawW, int drawH, Object interpolation) {
        BufferedImage out = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, x, y, drawW, drawH, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
