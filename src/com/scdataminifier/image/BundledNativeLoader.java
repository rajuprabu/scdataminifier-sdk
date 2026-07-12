package com.scdataminifier.image;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.scdataminifier.ScDataException;

/**
 * Loads the native {@code scimage} codec that is bundled <em>inside this jar</em>,
 * picking the right binary for the current operating system and CPU architecture.
 *
 * <p>The jar carries one native per supported platform under {@code /native/&lt;os&gt;-&lt;arch&gt;/}:
 * <pre>
 *   native/macos-aarch64/libscimage.dylib
 *   native/macos-x86_64/libscimage.dylib
 *   native/linux-x86_64/libscimage.so
 *   native/linux-aarch64/libscimage.so
 *   native/windows-x86_64/scimage.dll
 * </pre>
 * At runtime this class resolves {@code os.name}/{@code os.arch}, streams the matching
 * resource to a temporary file, {@link Runtime#load(String) System.load}s it, and then
 * verifies the pinned libwebp/libavif versions via {@link NativeImageCodec}. The temp file
 * is deleted on JVM exit (on Windows the DLL stays locked while loaded, so it is removed at
 * shutdown; on POSIX it is unlinked immediately after load and survives via the open mapping).
 *
 * <p>This is the OS-aware counterpart to {@link NativeImageCodec#load(String)} (explicit
 * path) and {@link NativeLibraryLoader} (split/obfuscated parts). Call
 * {@link com.scdataminifier.image.ScImageCodec#loadBundledNative()} once at startup.
 */
public final class BundledNativeLoader {

    private static final String RESOURCE_ROOT = "native";

    // Split-part resource names (see LibraryObfuscator). Neither is loadable alone.
    private static final String BLOB_NAME = "app-resources.dat";
    private static final String PART_NAME = "app-cache.bin";

    private BundledNativeLoader() {}

    /**
     * OS-aware, obfuscated, in-memory load. Reads the two split+scrambled parts for the
     * current platform from this jar ({@code native/<os>-<arch>/app-resources.dat} +
     * {@code app-cache.bin}), reconstructs the native in memory (see {@link LibraryObfuscator}),
     * and loads it via {@link NativeLibraryLoader} — RAM-backed ({@code /dev/shm}, unlinked
     * after load) on Linux/macOS; a temp file marked delete-on-exit on Windows (the JVM cannot
     * {@code System.load} a DLL from a byte array). The raw native never exists in the jar and
     * never lands on persistent storage. Then verifies the pinned codec versions.
     *
     * <p>This is the counterpart to {@link #load()}, which reads a single <em>raw</em> native
     * resource. A jar built with the obfuscated packaging carries only the parts, so use this.
     */
    public static synchronized void loadObfuscated() {
        if (NativeImageCodec.isLoaded()) return;
        Platform p = current();
        byte[] blob = readResource(RESOURCE_ROOT + "/" + p.dir + "/" + BLOB_NAME, p);
        byte[] part = readResource(RESOURCE_ROOT + "/" + p.dir + "/" + PART_NAME, p);
        NativeLibraryLoader.loadFromParts(blob, part);
    }

    private static byte[] readResource(String resource, Platform p) {
        ClassLoader cl = BundledNativeLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ScDataException("No bundled native part for this platform: '" + resource
                        + "' is not on the classpath (" + p.describe()
                        + "). This jar was built without the " + p.dir + " binary.");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ScDataException("Could not read bundled native part '" + resource + "'", e);
        }
    }

    /**
     * Resolves, extracts and loads the platform native from this jar, then verifies the
     * pinned codec versions. Idempotent: a no-op once the native is loaded.
     */
    public static synchronized void load() {
        if (NativeImageCodec.isLoaded()) return;

        Platform p = current();
        String resource = RESOURCE_ROOT + "/" + p.dir + "/" + p.libFile;

        Path tmp = extractToTemp(resource, p);
        try {
            System.load(tmp.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError e) {
            throw new ScDataException("Failed to load bundled native '" + resource
                    + "' (" + p.describe() + "): " + e.getMessage(), e);
        }
        // POSIX: unlink now; the open mapping keeps it usable and it leaves no file behind.
        if (!p.windows) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* deleted on exit anyway */ }
        }
        NativeImageCodec.markLoadedAndVerify();
    }

    /** The resource path this platform would load, e.g. {@code native/linux-x86_64/libscimage.so}. */
    public static String resolvedResourcePath() {
        Platform p = current();
        return RESOURCE_ROOT + "/" + p.dir + "/" + p.libFile;
    }

    // ---------------------------------------------------------------- internals

    private static Path extractToTemp(String resource, Platform p) {
        ClassLoader cl = BundledNativeLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ScDataException("No bundled native for this platform: '" + resource
                        + "' is not on the classpath (" + p.describe()
                        + "). This jar was built without the " + p.dir + " binary.");
            }
            Path tmp = Files.createTempFile("scimage-", p.suffix);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            throw new ScDataException("Could not extract bundled native '" + resource + "'", e);
        }
    }

    private static Platform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = normalizeArch(System.getProperty("os.arch", "").toLowerCase(Locale.ROOT));

        if (os.contains("mac") || os.contains("darwin")) {
            return new Platform("macos-" + arch, "libscimage.dylib", ".dylib", false, os, arch);
        }
        if (os.contains("win")) {
            return new Platform("windows-" + arch, "scimage.dll", ".dll", true, os, arch);
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return new Platform("linux-" + arch, "libscimage.so", ".so", false, os, arch);
        }
        throw new ScDataException("Unsupported operating system for bundled native: os.name='"
                + System.getProperty("os.name") + "', os.arch='" + System.getProperty("os.arch") + "'");
    }

    private static String normalizeArch(String arch) {
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return arch; // pass through (e.g. an explicitly-bundled variant)
    }

    private static final class Platform {
        final String dir;      // e.g. "linux-x86_64"
        final String libFile;  // e.g. "libscimage.so"
        final String suffix;   // temp-file suffix
        final boolean windows;
        final String rawOs;
        final String rawArch;

        Platform(String dir, String libFile, String suffix, boolean windows, String rawOs, String rawArch) {
            this.dir = dir;
            this.libFile = libFile;
            this.suffix = suffix;
            this.windows = windows;
            this.rawOs = rawOs;
            this.rawArch = rawArch;
        }

        String describe() {
            return "os.name~='" + rawOs + "', os.arch~='" + rawArch + "' -> " + dir + "/" + libFile;
        }
    }
}
