package com.scdataminifier.image;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import com.scdataminifier.ScDataException;

/**
 * Loads the native codec from two split+scrambled files (see
 * {@link LibraryObfuscator}) without the real library ever existing on disk
 * under its own name.
 *
 * Reconstruction happens in memory. Because no JVM can dlopen() a byte array,
 * the reconstructed bytes are written to a RAM-backed filesystem when one is
 * available (/dev/shm on Linux/Android - never touches persistent storage),
 * given a random innocuous name, loaded, and then immediately unlinked. On
 * POSIX the library stays mapped after unlink, so it vanishes from directory
 * listings while remaining usable; on Windows it is marked delete-on-exit.
 *
 * SECURITY NOTE: obfuscation only - the key lives in the caller. See
 * LibraryObfuscator's class comment.
 */
public final class NativeLibraryLoader {

    private NativeLibraryLoader() {}

    /**
     * Reconstructs and loads the native library, then verifies the pinned
     * codec versions (via NativeImageCodec). Safe to call once at startup.
     *
     * @param blob bytes of the "blob" file
     * @param part bytes of the "part" file
     */
    public static synchronized void loadFromParts(byte[] blob, byte[] part) {
        if (NativeImageCodec.isLoaded()) return;
        byte[] libBytes = LibraryObfuscator.reconstruct(blob, part);
        Path tmp = null;
        try {
            tmp = writeToVolatileFile(libBytes);
            // Zero the in-heap copy once it is on the RAM-backed fd.
            java.util.Arrays.fill(libBytes, (byte) 0);
            System.load(tmp.toAbsolutePath().toString());
            unlinkAfterLoad(tmp);
            tmp = null;
        } catch (IOException e) {
            throw new ScDataException("Failed to stage native library", e);
        } catch (UnsatisfiedLinkError e) {
            throw new ScDataException("Failed to load reconstructed native library", e);
        } finally {
            if (tmp != null) tryDelete(tmp);
        }
        NativeImageCodec.markLoadedAndVerify();
    }

    /** Convenience: read the two files from disk, then loadFromParts(). */
    public static void loadFromFiles(Path blobFile, Path partFile) {
        try {
            loadFromParts(Files.readAllBytes(blobFile), Files.readAllBytes(partFile));
        } catch (IOException e) {
            throw new ScDataException("Failed to read library part files", e);
        }
    }

    // ==================== staging ====================

    private static Path writeToVolatileFile(byte[] bytes) throws IOException {
        Path dir = volatileDir();
        // random, innocuous-looking name and extension
        String name = "." + Long.toHexString(mix()) + ".cache";
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        restrictPermissions(file);
        return file;
    }

    /** Prefer a RAM-backed tmpfs so bytes never reach persistent storage. */
    private static Path volatileDir() {
        String[] candidates = { "/dev/shm", "/run/user/" + userId(), System.getProperty("java.io.tmpdir") };
        for (String c : candidates) {
            if (c == null) continue;
            File d = new File(c);
            if (d.isDirectory() && d.canWrite()) return d.toPath();
        }
        throw new ScDataException("No writable staging directory found");
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX: fall back to File API
            File f = file.toFile();
            f.setReadable(true, true);
            f.setWritable(true, true);
            f.setExecutable(true, true);
        }
    }

    private static void unlinkAfterLoad(Path file) {
        // POSIX: the mapping survives unlink, so the file disappears from the
        // directory while the library keeps working.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            file.toFile().deleteOnExit(); // Windows keeps the DLL locked while loaded
        } else {
            tryDelete(file);
        }
    }

    private static void tryDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            file.toFile().deleteOnExit();
        }
    }

    private static long userId() {
        try {
            return (Long) Class.forName("com.sun.security.auth.module.UnixSystem")
                    .getMethod("getUid").invoke(
                            Class.forName("com.sun.security.auth.module.UnixSystem")
                                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long mix() {
        return (System.nanoTime() * 0x9E3779B97F4A7C15L) ^ Runtime.getRuntime().freeMemory();
    }
}
