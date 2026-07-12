package com.scdataminifier.image;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Minimal EXIF orientation handling for JPEG sources. Phone cameras store
 * the sensor image unrotated and record the display rotation in the EXIF
 * Orientation tag, which ImageIO ignores - without this, portrait photos
 * arrive sideways.
 */
public final class JpegExif {

    private JpegExif() {}

    /** @return EXIF orientation 1-8, or 1 (normal) when absent/not a JPEG. */
    public static int readOrientation(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return 1;
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) return 1;
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) return 1; // start of scan / end: no EXIF
            int segLen = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (marker == 0xE1 && pos + 4 + segLen - 2 <= jpeg.length && segLen >= 14
                    && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x' && jpeg[pos + 6] == 'i'
                    && jpeg[pos + 7] == 'f' && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                return orientationFromTiff(jpeg, pos + 10, segLen - 8);
            }
            pos += 2 + segLen;
        }
        return 1;
    }

    private static int orientationFromTiff(byte[] b, int tiff, int len) {
        if (len < 8) return 1;
        boolean le;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') le = true;
        else if (b[tiff] == 'M' && b[tiff + 1] == 'M') le = false;
        else return 1;
        long ifd = u32(b, tiff + 4, le);
        if (ifd + 2 > len) return 1;
        int count = u16(b, tiff + (int) ifd, le);
        int entry = tiff + (int) ifd + 2;
        for (int i = 0; i < count; i++, entry += 12) {
            if (entry + 12 > tiff + len) return 1;
            if (u16(b, entry, le) == 0x0112) { // Orientation tag
                int v = u16(b, entry + 8, le);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int off, boolean le) {
        int a = b[off] & 0xFF, c = b[off + 1] & 0xFF;
        return le ? (c << 8) | a : (a << 8) | c;
    }

    private static long u32(byte[] b, int off, boolean le) {
        long v = 0;
        for (int i = 0; i < 4; i++) v |= (long) (b[off + i] & 0xFF) << (le ? 8 * i : 8 * (3 - i));
        return v;
    }

    /** Returns the image rotated/mirrored so it displays upright (orientation 1). */
    public static BufferedImage normalize(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = orientation >= 5; // 5-8 involve a 90-degree rotation
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2: t.translate(w, 0); t.scale(-1, 1); break;                    // mirror horizontal
            case 3: t.translate(w, h); t.rotate(Math.PI); break;                 // 180
            case 4: t.translate(0, h); t.scale(1, -1); break;                    // mirror vertical
            case 5: t.rotate(Math.PI / 2); t.scale(1, -1); break;                // mirror + 90 CW
            case 6: t.translate(h, 0); t.rotate(Math.PI / 2); break;             // 90 CW
            case 7: t.translate(h, 0); t.rotate(Math.PI / 2); t.translate(w, 0); t.scale(-1, 1); break;
            case 8: t.translate(0, w); t.rotate(-Math.PI / 2); break;            // 90 CCW
        }
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
