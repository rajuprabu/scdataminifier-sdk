package com.scdataminifier.image;

import java.awt.image.BufferedImage;

import com.scdataminifier.enums.ImageType;

/**
 * Encodes a pixel image to a complete WEBP or AVIF file at a given quality.
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
}
