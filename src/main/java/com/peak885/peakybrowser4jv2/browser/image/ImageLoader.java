package com.peak885.peakybrowser4jv2.browser.image;

import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.zip.CRC32;

public final class ImageLoader {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    public ImageData load(InputStream input, String contentType) throws IOException {
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/gif")) {
            return GifImageLoader.load(input);
        }

        byte[] bytes = input.readAllBytes();
        boolean declaredPng = contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/png");
        boolean hasPngSignature = startsWith(bytes, PNG_SIGNATURE);
        String pngProblem = null;

        if (declaredPng || hasPngSignature) {
            if (!hasPngSignature) {
                pngProblem = "PNG content type has invalid signature; prefix=" + prefix(bytes);
            } else {
                pngProblem = validatePngChunks(bytes);
            }
            if (pngProblem != null) {
                Path dump = dumpDiagnostic(bytes);
                Logger.error("[IMAGE] Malformed PNG: {} ({} bytes, dump={})", pngProblem, bytes.length, dump);
            }
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            Path dump = (declaredPng || hasPngSignature) ? dumpDiagnostic(bytes) : null;
            Logger.warn(e, "[IMAGE] Image decoder rejected payload (type={}, size={}, dump={})", contentType, bytes.length, dump);
            throw new IOException("Invalid image payload (type=" + contentType + ", " + bytes.length + " bytes)", e);
        }

        if (image == null) {
            Path dump = (declaredPng || hasPngSignature) ? dumpDiagnostic(bytes) : null;
            Logger.warn("[IMAGE] Unsupported or invalid image payload (type={}, size={}, pngDiagnostic={}, dump={})",
                    contentType, bytes.length, pngProblem, dump);
            throw new IOException("Unsupported or invalid image payload (type=" + contentType + ", " + bytes.length + " bytes)");
        }
        return ImageData.staticImage(image);
    }

    private static String validatePngChunks(byte[] bytes) {
        int offset = PNG_SIGNATURE.length;
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) return "truncated PNG chunk header/data at offset " + offset;
            long length = Integer.toUnsignedLong(readInt(bytes, offset));
            if (length > bytes.length - offset - 12L) {
                return "PNG chunk length " + length + " exceeds remaining payload at offset " + offset;
            }
            int typeOffset = offset + 4;
            int dataOffset = offset + 8;
            int crcOffset = (int) (dataOffset + length);
            CRC32 crc = new CRC32();
            crc.update(bytes, typeOffset, (int) length + 4);
            long expectedCrc = Integer.toUnsignedLong(readInt(bytes, crcOffset));
            if (crc.getValue() != expectedCrc) {
                return "PNG chunk " + new String(bytes, typeOffset, 4, java.nio.charset.StandardCharsets.US_ASCII)
                        + " CRC mismatch at offset " + offset;
            }
            String type = new String(bytes, typeOffset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            offset = crcOffset + 4;
            if ("IEND".equals(type)) return offset == bytes.length ? null : "trailing bytes after PNG IEND chunk";
        }
        return "PNG has no IEND chunk";
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static String prefix(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 16));
    }

    private static Path dumpDiagnostic(byte[] bytes) {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Paths.get(System.getProperty("user.home"), "AppData", "Local", "PeakBrowser")
                : Paths.get(localAppData, "PeakBrowser");
        Path dump = root.resolve("diagnostics").resolve("invalid-image-" + UUID.randomUUID() + ".bin");
        try {
            Files.createDirectories(dump.getParent());
            Files.write(dump, bytes);
            return dump;
        } catch (IOException e) {
            Logger.warn(e, "[IMAGE] Could not write diagnostic image dump under {}", root);
            return null;
        }
    }
}
