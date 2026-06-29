package com.peak885.peakybrowser4j.browser.image;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.tinylog.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * ImageUploader using STBImage - NO Java image processing!
 * Direct disk → native ByteBuffer → GPU
 */
public final class ImageUploader {

    private static final int MAX_DIMENSION = 16384;
    private static final long MAX_PIXELS = 100_000_000L;

    private ImageUploader() {
    }

    /**
     * Load image file with STBImage (replaces ImageIO)
     * This is the ONLY image loading path now
     */
    public static int uploadSTB(long vg, String filePath, ImageEntry entry) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            Logger.info("[1/4] STBImage.stbi_load(): {}", filePath);

            // Load image directly to native ByteBuffer
            // Force 4 channels (RGBA)
            ByteBuffer imageData = STBImage.stbi_load(filePath, w, h, channels, 4);

            if (imageData == null) {
                Logger.error("[2/4] STBImage failed to load");
                return -1;
            }

            int width = w.get(0);
            int height = h.get(0);

            Logger.info("[2/4] Loaded: {}x{}", width, height);

            if (width <= 0 || height <= 0) {
                Logger.warn("Invalid dimensions {}x{}", width, height);
                STBImage.stbi_image_free(imageData);
                return -1;
            }

            if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                Logger.warn("Image too large ({}x{})", width, height);
                STBImage.stbi_image_free(imageData);
                return -1;
            }

            long pixels = (long) width * height;
            if (pixels > MAX_PIXELS) {
                Logger.warn("Refusing {} pixels", pixels);
                STBImage.stbi_image_free(imageData);
                return -1;
            }

            Logger.info("[3/4] Uploading to GPU...");

            // Upload directly (no conversion needed - STBImage gives us RGBA)
            int texture = nvgCreateImageRGBA(vg, width, height, 0, imageData);

            Logger.info("[4/4] Texture created: {}", texture);

            if (texture == 0) {
                Logger.warn("NanoVG failed to create texture");
                STBImage.stbi_image_free(imageData);
                return -1;
            }

            // Store image data so NanoVG can access it
            entry.imageBuffer = imageData;
            entry.width = width;
            entry.height = height;

            // FREE IMMEDIATELY after upload (Minecraft pattern)
            // NanoVG copies the data during nvgCreateImageRGBA
            STBImage.stbi_image_free(imageData);
            entry.imageBuffer = null;

            return texture;

        } catch (Throwable t) {
            Logger.error(t, "STBImage upload FAILED");
            return -1;
        }
    }

    /**
     * DEPRECATED: Old BufferedImage path - no longer used
     * Keeping for backwards compatibility if needed
     */
    @Deprecated
    public static int upload(long vg, Object image, ImageEntry entry) {
        Logger.warn("Using deprecated BufferedImage upload path!");
        return -1;
    }
}