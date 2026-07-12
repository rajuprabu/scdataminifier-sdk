package com.scdataminifier.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import com.scdataminifier.ScDataException;

/**
 * Build-time tool that hides a native library by applying a keyless,
 * reversible byte scramble and splitting the result across two
 * innocuous-looking files:
 *
 *   - a "blob" file: header + the first half of the scrambled bytes
 *   - a "part" file: the second half
 *
 * The scramble is a fixed-seed keystream XOR, so the bytes become
 * pseudo-random: no ASCII symbols, no ELF/Mach-O/PE magic, and neither file
 * is a recognizable or loadable library on its own. There is NO key and no
 * encryption - reconstruction is purely algorithmic. {@link NativeLibraryLoader}
 * reverses it at runtime entirely in memory.
 *
 * SECURITY NOTE: this is obfuscation, not protection. The scramble algorithm
 * and seed are in the code, so anyone who reads the (unobfuscated) code can
 * reverse it. It is meant to be paired with an outer JAR/APK obfuscator
 * (ProGuard/R8/DexGuard) that hides this logic; on its own it only stops
 * casual inspection (file name, strings, ldd). Real trust stays with the
 * payload signing/encryption keys.
 */
public final class LibraryObfuscator {

    private static final byte[] MAGIC = { 'S', 'C', 'B', '3' };

    // Fixed scramble seed. Not a secret - part of the algorithm. If you fork
    // the SDK, change it so your scramble differs from the stock one.
    private static final long SEED = 0xA5C3_9E27_11B4_7DF1L;

    private LibraryObfuscator() {}

    /** CLI: pack <libFile> <blobOut> <partOut> */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: LibraryObfuscator <libFile> <blobOut> <partOut>");
            return;
        }
        pack(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
        System.out.println("Packed " + args[0] + " -> " + args[1] + " + " + args[2]);
    }

    public static void pack(Path libFile, Path blobOut, Path partOut) throws IOException {
        byte[] lib = Files.readAllBytes(libFile);
        if (lib.length < 4096) throw new ScDataException("Library too small to split");

        byte[] scrambled = scramble(lib); // in place would mutate the read buffer; keep explicit
        int blobLen = scrambled.length / 2;

        byte[] blob = new byte[12 + blobLen];
        System.arraycopy(MAGIC, 0, blob, 0, 4);
        putU32(blob, 4, scrambled.length);
        putU32(blob, 8, blobLen);
        System.arraycopy(scrambled, 0, blob, 12, blobLen);

        byte[] part = Arrays.copyOfRange(scrambled, blobLen, scrambled.length);

        Files.write(blobOut, blob);
        Files.write(partOut, part);
    }

    /** Reverses pack() fully in memory; returns the original library bytes. */
    public static byte[] reconstruct(byte[] blob, byte[] part) {
        if (blob.length < 12 || blob[0] != MAGIC[0] || blob[1] != MAGIC[1]
                || blob[2] != MAGIC[2] || blob[3] != MAGIC[3]) {
            throw new ScDataException("Bad blob header");
        }
        int totalLen = getU32(blob, 4);
        int blobLen = getU32(blob, 8);
        if (blob.length != 12 + blobLen || blobLen + part.length != totalLen) {
            throw new ScDataException("Split library files are inconsistent or corrupted");
        }
        byte[] scrambled = new byte[totalLen];
        System.arraycopy(blob, 12, scrambled, 0, blobLen);
        System.arraycopy(part, 0, scrambled, blobLen, part.length);
        return scramble(scrambled); // XOR is its own inverse
    }

    // ==================== keyless scramble ====================

    /** Fixed-seed splitmix64 keystream XOR. Self-inverse. */
    private static byte[] scramble(byte[] in) {
        byte[] out = new byte[in.length];
        long s = SEED;
        for (int i = 0; i < in.length; i++) {
            s += 0x9E3779B97F4A7C15L;
            long z = s;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            out[i] = (byte) (in[i] ^ (byte) z);
        }
        return out;
    }

    // ==================== helpers ====================

    private static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }

    private static int getU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
