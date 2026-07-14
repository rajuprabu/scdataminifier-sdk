package com.scdataminifier.image;

import java.awt.image.BufferedImage;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.ImageType;
import com.scdataminifier.model.ScImage;

/**
 * ImageEncoder backed by the bundled native scimage library (statically
 * linked libwebp + libavif/aom at pinned versions - see native/README.md).
 * Used for BOTH encoding and decoding so the exact same codec bits run in
 * every environment; loading verifies the pinned versions and fails loudly
 * on mismatch.
 *
 * The same class works on Android (package libscimage.so under jniLibs and
 * call loadDefault()); the BufferedImage helpers are desktop-only, Android
 * apps use the byte[]-based methods with Bitmap instead.
 */
public final class NativeImageCodec implements ImageEncoder {

    /** Versions this SDK build is pinned to; load() rejects anything else. */
    public static final String PINNED_WEBP_VERSION = "1.6.0";
    public static final String PINNED_AVIF_VERSION = "1.4.2";

    /* Was 6. Lowered for better rate-distortion quality (less blocky/smoother output at small
     * QR-thumbnail sizes) — measured improvement via A/B testing (encoded output size differs:
     * 1365B @ speed 6 vs 1393B @ speed 2 for the same source/quality-search, and the decoded
     * image is visibly smoother in flat regions). Cost is server-side encode CPU time only,
     * paid once per credential issuance (not a hot path), so the slower/better preset is a
     * clear win here. 0 is slowest/best; this SDK's range is 0-10. */
    public static final int AVIF_SPEED = 2;

    private static volatile boolean loaded;

    /** Creates an encoder instance; the native library must be loaded first. */
    public NativeImageCodec() {
        if (!loaded) throw new ScDataException("Native scimage library not loaded; call NativeImageCodec.load(path) first");
    }

    // ==================== library loading ====================

    /** Loads the native library from an explicit path and verifies the pinned versions. */
    public static synchronized void load(String libraryPath) {
        if (loaded) return;
        String absolute = new java.io.File(libraryPath).getAbsolutePath();
        try {
            System.load(absolute);
        } catch (UnsatisfiedLinkError e) {
            throw new ScDataException("Failed to load native scimage library from " + absolute, e);
        }
        verifyPinnedVersions();
        loaded = true;
    }

    /** Loads via System.loadLibrary("scimage") - java.library.path / Android jniLibs. */
    public static synchronized void loadDefault() {
        if (loaded) return;
        try {
            System.loadLibrary("scimage");
        } catch (UnsatisfiedLinkError e) {
            throw new ScDataException("Failed to load native 'scimage' library from java.library.path", e);
        }
        verifyPinnedVersions();
        loaded = true;
    }

    public static boolean isLoaded() { return loaded; }

    /**
     * Marks the native library loaded and verifies pinned versions. Used by
     * NativeLibraryLoader after it has System.load()ed a reconstructed library
     * itself; do not call directly.
     */
    static synchronized void markLoadedAndVerify() {
        if (loaded) return;
        verifyPinnedVersions();
        loaded = true;
    }

    private static void verifyPinnedVersions() {
        String webp = webpVersion();
        String avif = avifVersion();
        if (!PINNED_WEBP_VERSION.equals(webp) || !PINNED_AVIF_VERSION.equals(avif)) {
            throw new ScDataException("Native codec version mismatch: libwebp=" + webp
                    + " libavif=" + avif + " (pinned: " + PINNED_WEBP_VERSION + "/" + PINNED_AVIF_VERSION
                    + ", codecs: " + codecVersions() + ")");
        }
    }

    // ==================== natives ====================

    public static native String webpVersion();
    public static native String avifVersion();
    /** Underlying AV1 codec versions, e.g. "aom [enc/dec]:3.14.1". */
    public static native String codecVersions();

    private static native byte[] nEncodeWebp(byte[] rgb, int width, int height, int quality);
    private static native byte[] nEncodeAvif(byte[] rgb, int width, int height, int quality, int speed);
    private static native byte[] nDecodeWebp(byte[] data, int[] dims);
    private static native byte[] nDecodeAvif(byte[] data, int[] dims);

    static native int nLicenseInit(byte[] license, String packageName);
    static native boolean nLicenseOk();

    // ==================== licensing ====================

    /**
     * Applies a signed license to the native library. Must succeed before any encode/decode —
     * the .so refuses value operations until a valid license bound to {@code packageName} is
     * accepted. The library verifies the signature with its embedded public key and enforces
     * the licensed package name and validity dates.
     *
     * @param license     raw license bytes (the base64-decoded body of a .lic file)
     * @param packageName the calling app's Android applicationId / iOS bundle identifier
     * @return 0 on success (SC_LIC_OK); negative on failure (-2 signature, -3 package, -4 not
     *         yet valid, -5 expired, -1 malformed)
     */
    public static int applyLicense(byte[] license, String packageName) {
        return nLicenseInit(license, packageName);
    }

    /** True once a valid license has been accepted by {@link #applyLicense}. */
    public static boolean isLicensed() {
        return nLicenseOk();
    }

    // ==================== byte-level API (desktop + Android) ====================

    /** Encodes 24-bit RGB pixels (3 bytes/pixel, row-major) to a complete image file. */
    public static byte[] encodeRgb(byte[] rgb, int width, int height, ImageType type, int quality) {
        if (!loaded) throw new ScDataException("Native scimage library not loaded");
        return type == ImageType.WEBP
                ? nEncodeWebp(rgb, width, height, quality)
                : nEncodeAvif(rgb, width, height, quality, AVIF_SPEED);
    }

    /** Decodes a complete image file to 24-bit RGB; dims[0]/dims[1] receive width/height. */
    public static byte[] decodeToRgb(byte[] imageFile, ImageType type, int[] dims) {
        if (!loaded) throw new ScDataException("Native scimage library not loaded");
        return type == ImageType.WEBP ? nDecodeWebp(imageFile, dims) : nDecodeAvif(imageFile, dims);
    }

    // ==================== desktop helpers ====================

    /** ImageEncoder for ScImageCodec: encodes a BufferedImage via the native codec. */
    @Override
    public byte[] encode(BufferedImage image, ImageType type, int quality) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0, o = 0; i < argb.length; i++) {
            rgb[o++] = (byte) (argb[i] >> 16);
            rgb[o++] = (byte) (argb[i] >> 8);
            rgb[o++] = (byte) argb[i];
        }
        return encodeRgb(rgb, w, h, type, quality);
    }

    /** Decodes an ScImage (reconstructing the container) to a BufferedImage. */
    public static BufferedImage decodeToImage(ScImage image) {
        int[] dims = new int[2];
        byte[] rgb = decodeToRgb(image.toImageBytes(), image.getType(), dims);
        int w = dims[0], h = dims[1];
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] argb = new int[w * h];
        for (int i = 0, o = 0; i < argb.length; i++) {
            argb[i] = ((rgb[o++] & 0xFF) << 16) | ((rgb[o++] & 0xFF) << 8) | (rgb[o++] & 0xFF);
        }
        out.setRGB(0, 0, w, h, argb, 0, w);
        return out;
    }
}
