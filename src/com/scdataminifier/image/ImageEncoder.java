package com.scdataminifier.image;

import java.awt.image.BufferedImage;

import com.scdataminifier.enums.ImageType;

/**
 * Encodes a pixel image to a complete CODEC_A or CODEC_B file at a given quality.
 * The default desktop implementation shells out to cwebp/avifenc; on Android
 * implement this with Bitmap.compress and install it via
 * ScImageCodec.setEncoder().
 */
public interface ImageEncoder {

    /**
     * @param image   the (already scaled) image to encode
     * @param type    target format
     * @param quality 1 (smallest) to 100 (best)
     * @return complete image file bytes including container header
     */
    byte[] encode(BufferedImage image, ImageType type, int quality);

    /**
     * Optional rate-controlled encode: the encoder's own rate-control aims the complete
     * file at {@code targetBytes} (a goal, not a hard cap — slight overshoot is allowed;
     * the caller re-tries smaller). Encoders without rate-control return {@code null} and
     * the caller falls back to a fixed-quality search via {@link #encode}.
     */
    default byte[] encodeTarget(BufferedImage image, ImageType type, int targetBytes) {
        return null;
    }
}
