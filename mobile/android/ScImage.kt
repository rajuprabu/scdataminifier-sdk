package com.scdataminifier.image

import java.io.ByteArrayOutputStream

/**
 * Viewer-side reconstruction of an SCDataMinifier IMAGE value into a complete
 * WEBP/AVIF file, byte-identical to the Java `model.ScImage` +
 * `util.ImageContainers` build path. Viewers only rebuild (never strip), so
 * this is all the Android side needs beyond the OS decoder. Pure Kotlin.
 *
 * Usage:
 *   val img = ScImage.parse(valueBytes)          // the IMAGE tlv/cell/variable value
 *   val fileBytes = img.toImageBytes()           // complete .webp / .avif
 *   val bitmap = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
 *   // WEBP: all Android; AVIF: Android 12+ (API 31) via ImageDecoder.
 */
object ScImage {

    enum class ImageType(val code: Int) {
        WEBP(0), AVIF(1);
        companion object {
            fun fromCode(c: Int): ImageType =
                values().firstOrNull { it.code == c }
                    ?: throw IllegalArgumentException("Unknown image type code $c")
        }
    }

    class Decoded(
        val type: ImageType,
        val headerPresent: Boolean,
        val version: Int,
        val data: ByteArray
    ) {
        /** Complete .webp/.avif file bytes, container rebuilt when stripped. */
        fun toImageBytes(): ByteArray {
            if (headerPresent) return data
            require(version == 1) { "Unsupported image version $version (only 1 is defined)" }
            return if (type == ImageType.WEBP) buildWebpV1(data) else buildAvifV1(data)
        }
    }

    /** Parse an IMAGE value (image-header byte + data). */
    fun parse(value: ByteArray): Decoded {
        require(value.isNotEmpty()) { "Image value is empty" }
        val b = value[0].toInt() and 0xFF
        val type = ImageType.fromCode(b and 0x0F)
        val headerPresent = (b and 0x10) != 0
        val version = (b shr 5) and 0x07
        return Decoded(type, headerPresent, version, value.copyOfRange(1, value.size))
    }

    // ---- WebP v1 ----

    fun buildWebpV1(payload: ByteArray): ByteArray {
        val pad = payload.size and 1
        val out = ByteArrayOutputStream()
        out.ascii("RIFF"); out.le32(4 + 8 + payload.size + pad)
        out.ascii("WEBP"); out.ascii("VP8 "); out.le32(payload.size)
        out.write(payload); if (pad == 1) out.write(0)
        return out.toByteArray()
    }

    // ---- AVIF v1 ----

    // Fixed boxes exactly as produced by libavif for a single 8-bit 4:2:0
    // (AV1 Main profile, brand MA1B) sRGB image.
    private val FTYP = hex("00000020667479706176696600000000617669666d6966316d6961664d413142")
    private val HDLR = hex("0000002168646c7200000000000000007069637400000000000000000000000000")
    private val PITM = hex("0000000e7069746d000000000001")
    private val IINF = hex("0000002869696e660000000000010000001a696e6665020000000001000061763031436f6c6f7200")
    private val PIXI = hex("00000010706978690000000003080808")
    private val COLR = hex("00000013636f6c726e636c780001000d000680")
    private val IPMA = hex("0000001769706d61000000000000000100010401028304")

    fun buildAvifV1(data: ByteArray): ByteArray {
        var p = 0
        fun u16(): Int { val v = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF); p += 2; return v }
        val width = u16(); val height = u16()
        val av1cLen = data[p].toInt() and 0xFF; p += 1
        val av1c = data.copyOfRange(p, p + av1cLen); p += av1cLen
        val payload = data.copyOfRange(p, data.size)

        val ispe = box("ispe", hex("00000000"), u32be(width), u32be(height))
        val av1C = box("av1C", av1c)
        val ipco = box("ipco", ispe, PIXI, av1C, COLR)
        val iprp = box("iprp", ipco, IPMA)

        val metaSize = 12 + HDLR.size + PITM.size + 30 + IINF.size + iprp.size
        val shellSize = FTYP.size + metaSize + 8
        val ilocFixed = hex("0000001e696c6f630000000044000001000100000001")

        val out = ByteArrayOutputStream()
        out.write(FTYP)
        out.write(u32be(metaSize)); out.ascii("meta"); out.write(hex("00000000"))
        out.write(HDLR); out.write(PITM)
        out.write(ilocFixed); out.write(u32be(shellSize)); out.write(u32be(payload.size))
        out.write(IINF); out.write(iprp)
        out.write(u32be(8 + payload.size)); out.ascii("mdat"); out.write(payload)
        return out.toByteArray()
    }

    // ---- helpers ----

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.le32(v: Int) =
        write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))

    private fun u32be(v: Int): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var len = 8
        for (part in parts) len += part.size
        val out = ByteArrayOutputStream()
        out.write(u32be(len))
        out.write(type.toByteArray(Charsets.US_ASCII))
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
