package com.scdataminifier.decoder

import android.graphics.Bitmap

/**
 * Decode-only image SDK (Android). Turns a stored IMAGE value — the 1-byte descriptor plus the
 * version-1 headerless data carried in an SCDataMinifier TLV — into an Android [Bitmap], gated
 * by a license. The underlying image format is never named here or in the native library; the
 * app links whichever flavour (A or B) matches the images it must display.
 *
 * Packaging: put the flavour's `libscdec.so` under `src/main/jniLibs/<abi>/`. Both flavours use
 * the same class/symbol names, so an app links exactly one.
 *
 * ```
 * ScDecoder.license(licBytes, BuildConfig.APPLICATION_ID)   // once, at startup
 * val bmp = ScDecoder.decode(imageValueFromTlv)             // per image
 * ```
 */
object ScDecoder {

    init {
        System.loadLibrary("scdec")
    }

    /** Applies a signed license bound to [packageName]; returns 0 on success, negative otherwise. */
    fun license(license: ByteArray, packageName: String): Int = nLicense(license, packageName)

    /** True once a valid license has been accepted. */
    val isLicensed: Boolean get() = nLicensed()

    /** Pixel dimensions of an IMAGE value without decoding, or null on failure. */
    fun size(imageValue: ByteArray): Pair<Int, Int>? =
        nInfo(imageValue)?.let { it[0] to it[1] }

    /**
     * Decodes an IMAGE value to an ARGB_8888 [Bitmap] (opaque), or null on failure
     * (e.g. not licensed, or malformed value).
     */
    fun decode(imageValue: ByteArray): Bitmap? {
        val packed = nOpen(imageValue) ?: return null
        val w = ((packed[0].toInt() and 0xFF) shl 8) or (packed[1].toInt() and 0xFF)
        val h = ((packed[2].toInt() and 0xFF) shl 8) or (packed[3].toInt() and 0xFF)
        val pixels = IntArray(w * h)
        var p = 4
        for (i in 0 until w * h) {
            val r = packed[p].toInt() and 0xFF
            val g = packed[p + 1].toInt() and 0xFF
            val b = packed[p + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p += 3
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private external fun nLicense(license: ByteArray, packageName: String): Int
    private external fun nLicensed(): Boolean
    private external fun nInfo(value: ByteArray): IntArray?
    private external fun nOpen(value: ByteArray): ByteArray?
}
