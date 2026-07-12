package com.scdataminifier.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.scdataminifier.ScDataException;
import com.scdataminifier.enums.ImageType;

/**
 * Desktop ImageEncoder backed by the libwebp/libavif command line tools
 * (cwebp, avifenc). Install on macOS with: brew install webp libavif
 *
 * The container-strip scheme (image version 1) requires the encoder output
 * layout to stay stable, so encoder versions can be pinned: construct with
 * the required tool versions and every encode first verifies the installed
 * tools match, failing loudly on drift instead of producing payloads the
 * pinned viewers were never tested against.
 */
public class CliImageEncoder implements ImageEncoder {

    private static final String[] SEARCH_DIRS = { "", "/opt/homebrew/bin/", "/usr/local/bin/", "/usr/bin/" };
    private static final Map<String, String> TOOL_CACHE = new HashMap<String, String>();
    private static final Map<String, String> TOOL_OVERRIDES = new HashMap<String, String>();
    private static final Map<String, String> VERSION_CACHE = new HashMap<String, String>();
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private final String requiredCwebpVersion;   // null = not pinned
    private final String requiredAvifencVersion; // null = not pinned

    /** Unpinned: any installed tool version is accepted. */
    public CliImageEncoder() {
        this(null, null);
    }

    /** Pinned: encode fails unless the installed tools match exactly (null skips that tool's check). */
    public CliImageEncoder(String requiredCwebpVersion, String requiredAvifencVersion) {
        this.requiredCwebpVersion = requiredCwebpVersion;
        this.requiredAvifencVersion = requiredAvifencVersion;
    }

    /**
     * Points a tool ("cwebp" or "avifenc") at an explicit binary - use this
     * in production to run the application-bundled, version-pinned build
     * (e.g. /opt/yourapp/bin/cwebp on RHEL) instead of whatever is on PATH.
     */
    public static synchronized void setToolPath(String toolName, String absolutePath) {
        if (!new File(absolutePath).canExecute()) {
            throw new ScDataException(toolName + " binary not executable: " + absolutePath);
        }
        TOOL_OVERRIDES.put(toolName, absolutePath);
        TOOL_CACHE.remove(toolName);
        VERSION_CACHE.remove(toolName);
    }

    @Override
    public byte[] encode(BufferedImage image, ImageType type, int quality) {
        if (quality < 1 || quality > 100) throw new ScDataException("Quality must be 1-100");
        verifyPin(type);
        File in = null, out = null;
        try {
            in = File.createTempFile("scimg-in", ".png");
            out = File.createTempFile("scimg-out", type == ImageType.WEBP ? ".webp" : ".avif");
            if (!ImageIO.write(image, "png", in)) throw new ScDataException("Failed to write temp PNG");
            // Tuned for face/portrait photos; -noalpha keeps WebP simple lossy
            // (VP8) so the version-1 container strip applies.
            String[] cmd;
            if (type == ImageType.WEBP) {
                cmd = new String[] { tool("cwebp"), "-quiet", "-preset", "picture", "-m", "6", "-noalpha",
                        "-q", String.valueOf(quality),
                        in.getAbsolutePath(), "-o", out.getAbsolutePath() };
            } else {
                // --yuv 420 forces AV1 Main profile (8-bit 4:2:0) - the only
                // profile every phone decoder (incl. hardware) must support.
                cmd = new String[] { tool("avifenc"), "-q", String.valueOf(quality), "--speed", "6",
                        "--yuv", "420",
                        in.getAbsolutePath(), out.getAbsolutePath() };
            }
            run(cmd);
            return Files.readAllBytes(out.toPath());
        } catch (IOException e) {
            throw new ScDataException("Image encoding failed", e);
        } finally {
            if (in != null) in.delete();
            if (out != null) out.delete();
        }
    }

    private void verifyPin(ImageType type) {
        String required = type == ImageType.WEBP ? requiredCwebpVersion : requiredAvifencVersion;
        if (required == null) return;
        String installed = detectVersion(type);
        if (!required.equals(installed)) {
            throw new ScDataException((type == ImageType.WEBP ? "cwebp" : "avifenc")
                    + " version mismatch: required " + required + " but installed " + installed
                    + " - image version 1 payloads must be produced with the pinned encoder");
        }
    }

    /** Version (x.y.z) of the installed encoder for the given format. */
    public static synchronized String detectVersion(ImageType type) {
        String name = type == ImageType.WEBP ? "cwebp" : "avifenc";
        String cached = VERSION_CACHE.get(name);
        if (cached != null) return cached;
        String flag = type == ImageType.WEBP ? "-version" : "--version";
        try {
            Process p = new ProcessBuilder(tool(name), flag).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) > 0) output.write(buf, 0, n);
            p.waitFor();
            Matcher m = VERSION_PATTERN.matcher(output.toString());
            if (!m.find()) throw new ScDataException("Could not parse " + name + " version from: " + output.toString().trim());
            VERSION_CACHE.put(name, m.group(1));
            return m.group(1);
        } catch (IOException e) {
            throw new ScDataException("Failed to run " + name + " " + flag, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScDataException(name + " interrupted", e);
        }
    }

    private static void run(String[] cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream is = p.getInputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) output.write(buf, 0, n);
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScDataException(cmd[0] + " interrupted", e);
        }
        if (exit != 0) {
            throw new ScDataException(cmd[0] + " failed (exit " + exit + "): " + output.toString().trim());
        }
    }

    private static synchronized String tool(String name) {
        String override = TOOL_OVERRIDES.get(name);
        if (override != null) return override;
        String cached = TOOL_CACHE.get(name);
        if (cached != null) return cached;
        for (String dir : SEARCH_DIRS) {
            String candidate = dir + name;
            if (dir.isEmpty()) {
                try {
                    new ProcessBuilder(candidate, "-version").start().destroy();
                    TOOL_CACHE.put(name, candidate);
                    return candidate;
                } catch (IOException ignored) { /* not on PATH */ }
            } else if (new File(candidate).canExecute()) {
                TOOL_CACHE.put(name, candidate);
                return candidate;
            }
        }
        throw new ScDataException("'" + name + "' not found; install it (macOS: brew install "
                + ("cwebp".equals(name) ? "webp" : "libavif") + ") or provide your own ImageEncoder via ScImageCodec.setEncoder()");
    }
}
