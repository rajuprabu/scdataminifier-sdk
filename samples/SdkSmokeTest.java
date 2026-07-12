import com.scdataminifier.enums.ImageType;
import com.scdataminifier.image.NativeImageCodec;
import com.scdataminifier.image.ScImageCodec;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Minimal end-to-end test of the SCDataMinifier SDK jar:
 *   1. loads the OS-specific native codec bundled (split + scrambled) inside the jar,
 *   2. encodes a synthetic RGB image to WEBP and AVIF via the native codec,
 *   3. decodes the WEBP back and checks the round-trip dimensions.
 *
 * No input image or external native file is needed. Compile and run against the jar:
 *   javac -cp scdataminifier.jar samples/SdkSmokeTest.java
 *   java  -cp scdataminifier.jar:samples SdkSmokeTest
 */
public class SdkSmokeTest {

    public static void main(String[] args) throws Exception {
        System.out.println("OS      : " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        System.out.println("Java    : " + System.getProperty("java.version"));

        // 1) Load the native codec from the scrambled parts in the jar (reconstructed in memory).
        ScImageCodec.loadBundledObfuscatedNative();
        System.out.println("Native  : loaded=" + NativeImageCodec.isLoaded());
        System.out.println("Codec   : " + NativeImageCodec.codecVersions());

        // 2) Build a 64x64 RGB gradient (no input file required).
        int w = 64, h = 64;
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                rgb[i]     = (byte) (x * 4);         // R
                rgb[i + 1] = (byte) (y * 4);         // G
                rgb[i + 2] = (byte) ((x + y) * 2);   // B
            }
        }

        // 3) Encode via the native codec and write the outputs.
        byte[] webp = NativeImageCodec.encodeRgb(rgb, w, h, ImageType.WEBP, 80);
        byte[] avif = NativeImageCodec.encodeRgb(rgb, w, h, ImageType.AVIF, 60);
        Files.write(Paths.get("out.webp"), webp);
        Files.write(Paths.get("out.avif"), avif);
        System.out.println("Encode  : WEBP=" + webp.length + "B -> out.webp,  AVIF=" + avif.length + "B -> out.avif");

        // 4) Decode the WEBP back to pixels and confirm the round-trip size.
        int[] dims = new int[2];
        byte[] back = NativeImageCodec.decodeToRgb(webp, ImageType.WEBP, dims);
        System.out.println("Decode  : WEBP -> " + dims[0] + "x" + dims[1] + " (" + back.length + " RGB bytes)");
        if (dims[0] != w || dims[1] != h) {
            throw new IllegalStateException("round-trip size mismatch: got " + dims[0] + "x" + dims[1]);
        }

        System.out.println("RESULT  : SUCCESS - native codec works end-to-end");
    }
}
