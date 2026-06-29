package com.peak885.peakybrowser4j.browser.image;

import com.peak885.peakybrowser4j.browser.PageLoader;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ImageSystem with DISK caching + MAIN THREAD GPU uploads
 * - Background threads: Download to disk only (thread-safe)
 * - Main thread: Load from disk + upload to GPU (NanoVG is thread-safe on main thread only)
 */
public class ImageSystem {
    private static final ExecutorService loaderPool = Executors.newFixedThreadPool(4);

    // GPU uploads to be processed by main thread
    private static final LinkedBlockingQueue<GpuUploadTask> gpuUploadQueue = new LinkedBlockingQueue<>();

    // Only GPU handles in memory (NOT BufferedImages!)
    private static final Map<String, ImageEntry> gpuCache = new ConcurrentHashMap<>();

    private static PageLoader loader;
    private static Path cacheDir;
    private static long vgContext = 0;

    private static final long MAX_DISK_CACHE_BYTES = 500 * 1024 * 1024;

    // Task for main thread to process
    private static class GpuUploadTask {
        String url;
        Path cacheFile;
        ImageEntry entry;

        GpuUploadTask(String url, Path cacheFile, ImageEntry entry) {
            this.url = url;
            this.cacheFile = cacheFile;
            this.entry = entry;
        }
    }

    public static void init(PageLoader pageLoader, long vg) {
        loader = pageLoader;
        vgContext = vg;

        cacheDir = getCacheDirectory();

        try {
            Files.createDirectories(cacheDir);
            Logger.info("Image cache directory: {}", cacheDir.toAbsolutePath());
        } catch (IOException e) {
            Logger.error("Failed to create cache directory: {}", e.getMessage());
        }
    }

    private static Path getCacheDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata == null) appdata = userHome;
            return Paths.get(appdata, "PeakyBrowser4J", "cache", "images");
        } else if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Caches", "PeakyBrowser4J", "images");
        } else {
            return Paths.get(userHome, ".cache", "peakybrowser4j", "images");
        }
    }

    /**
     * Request loading an image (async disk download only)
     */
    public static void requestLoad(String url) {
        if (url == null) return;

        if (gpuCache.containsKey(url)) return;

        ImageEntry entry = new ImageEntry(url);
        if (gpuCache.putIfAbsent(url, entry) != null) {
            return;
        }

        // Download to disk in background (thread-safe)
        loaderPool.submit(() -> {
            try {
                loadImageToDisk(url, entry);
            } catch (Exception e) {
                Logger.error("Failed to load image {}: {}", url, e.getMessage());
                entry.failed = true;
            }
        });
    }

    /**
     * Get GPU texture handle (blocking if still loading)
     */
    public static int getTexture(long vg, String url) {
        ImageEntry entry = gpuCache.get(url);
        if (entry == null) return -1;

        entry.touch();

        int maxWait = 100; // ms
        for (int i = 0; i < maxWait && entry.textureId == -1 && !entry.failed; i++) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return entry.textureId;
    }

    /**
     * BACKGROUND THREAD: Download image to disk only
     */
    private static void loadImageToDisk(String url, ImageEntry entry) throws Exception {
        Path cacheFile = getCacheFile(url);

        if (!Files.exists(cacheFile)) {
            Logger.info("Downloading image: {}", url);
            downloadImageToDisk(url, cacheFile);
        } else {
            Logger.info("Using cached image: {}", url);
        }

        if (!Files.exists(cacheFile)) {
            Logger.error("Failed to cache image: {}", url);
            entry.failed = true;
            return;
        }

        // Check disk cache size
        if (getDiskCacheSize() > MAX_DISK_CACHE_BYTES) {
            Logger.warn("Disk cache full, evicting old files");
            evictOldestCacheFiles();
        }

        // Queue GPU upload for main thread (NOT doing it here!)
        entry.cachePath = cacheFile;
        gpuUploadQueue.offer(new GpuUploadTask(url, cacheFile, entry));
        Logger.info("Queued GPU upload for: {}", url);
    }

    /**
     * Download image bytes and save to disk (thread-safe)
     */
    private static void downloadImageToDisk(String url, Path cacheFile) throws IOException {
        byte[] imageBytes = loader.downloadImageBytes(url);
        if (imageBytes == null) {
            throw new IOException("Download returned null");
        }

        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, imageBytes);
        Logger.info("Saved {} bytes to {}", imageBytes.length, cacheFile.getFileName());
    }

    /**
     * MAIN THREAD: Process GPU uploads from queue
     * Call this once per frame in the render loop
     */
    public static void processGpuUploads() {

//        Logger.info("GPU Queue Size = {}", gpuUploadQueue.size());

        GpuUploadTask task = gpuUploadQueue.poll();

        if (task == null) {
            return;
        }

//        Logger.info("Processing upload for {}", task.url);

        try {
            uploadToGpuMainThread(task.cacheFile, task.entry);
        } catch (Exception e) {
            Logger.error("Failed to upload {} to GPU: {}", task.url, e.getMessage());
            task.entry.failed = true;
        }

//        Logger.info("Finished upload for {}", task.url);
    }

    private static Path getCacheFile(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(url.getBytes());

        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        String filename = sb.toString().substring(0, 16) + ".png";

        return cacheDir.resolve(filename);
    }

    private static long getDiskCacheSize() {
        try {
            return Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void evictOldestCacheFiles() {
        try {
            Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> {
                        try {
                            long timeA = Files.getLastModifiedTime(a).toMillis();
                            long timeB = Files.getLastModifiedTime(b).toMillis();
                            return Long.compare(timeA, timeB);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .limit(20)
                    .forEach(p -> {
                        try {
                            long size = Files.size(p);
                            Files.delete(p);
                            Logger.info("Evicted cache file: {} ({} KB)",
                                    p.getFileName(), size / 1024);
                        } catch (IOException e) {
                            Logger.error("Failed to delete cache file: {}", e.getMessage());
                        }
                    });
        } catch (IOException e) {
            Logger.error("Error evicting cache files: {}", e.getMessage());
        }
    }

    /**
     * FIXED: Cleanup both GPU textures AND their buffers
     */
    public static void cleanup(long vg, long maxIdleMs) {
        long now = System.currentTimeMillis();

        gpuCache.entrySet().removeIf(e -> {
            ImageEntry entry = e.getValue();

            if (now - entry.lastAccessTime > maxIdleMs) {
                Logger.info("Cleaning up idle texture: {}", e.getKey());

                // 1. Delete GPU texture
                if (entry.textureId != -1) {
                    org.lwjgl.nanovg.NanoVG.nvgDeleteImage(vg, entry.textureId);
                    Logger.info("  Deleted GPU texture ID: {}", entry.textureId);
                    entry.textureId = -1;
                }

                // 2. Free off-heap buffer (CRITICAL!)
                if (entry.imageBuffer != null) {
                    org.lwjgl.system.MemoryUtil.memFree(entry.imageBuffer);
                    entry.imageBuffer = null;
                }

                return true;
            }

            return false;
        });
    }

    /**
     * FIXED: clearAll() must free buffers too
     */
    public static void clearAll(long vg) {
        Logger.info("Clearing {} GPU textures + buffers (disk cache preserved)", gpuCache.size());

        for (ImageEntry entry : gpuCache.values()) {
            if (entry.textureId != -1) {
                org.lwjgl.nanovg.NanoVG.nvgDeleteImage(vg, entry.textureId);
                Logger.info("  Deleted GPU texture ID: {}", entry.textureId);
            }

            // FREE THE BUFFER!
            if (entry.imageBuffer != null) {
                org.lwjgl.system.MemoryUtil.memFree(entry.imageBuffer);
            }
        }

        gpuCache.clear();
    }

    public static void clearDiskCache() {
        try {
            Logger.warn("Clearing disk cache: {}", cacheDir);
            Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            Logger.error("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            Logger.error("Failed to clear disk cache: {}", e.getMessage());
        }
    }

    // Add these methods to ImageSystem.java

    /**
     * MAIN THREAD ONLY: Load from disk and upload to GPU
     */
    private static void uploadToGpuMainThread(Path cacheFile, ImageEntry entry) {
        try {
            Logger.info("[A] uploadToGpuMainThread called");
            Logger.info("[B] Checking cache file: {}", cacheFile.getFileName());

            if (!Files.exists(cacheFile) || Files.size(cacheFile) == 0) {
                throw new IOException("Cache file missing or empty: " + cacheFile);
            }

            Logger.info("[C] Cache file exists, size={} bytes", Files.size(cacheFile));
            Logger.info("[D] Loading with STBImage.stbi_load()...");

            // Use STBImage instead of ImageIO - NO BufferedImage!
            int textureId = ImageUploader.uploadSTB(vgContext, cacheFile.toAbsolutePath().toString(), entry);

            if (textureId == -1) {
                throw new IOException("STBImage load failed");
            }

            entry.textureId = textureId;
            Logger.info("GPU UPLOAD: Success! Texture ID={}", textureId);

        } catch (Exception e) {
            Logger.error("CRITICAL: Failed to upload to GPU: {}", e.getMessage());
            e.printStackTrace();
            entry.failed = true;
            try { Files.deleteIfExists(cacheFile); } catch (IOException ignored) {}
        }
    }

    /**
     * Load a specific frame from a GIF file
     * @param gifFile Path to GIF file
     * @param frameIndex Which frame to load (0 = first)
     * @return BufferedImage of that frame, or null if failed
     */
    private static BufferedImage loadGifFrame(Path gifFile, int frameIndex) {
        try {
            com.madgag.gif.fmsware.GifDecoder decoder =
                    new com.madgag.gif.fmsware.GifDecoder();

            int status = decoder.read(gifFile.toFile().getAbsolutePath());

            if (status != com.madgag.gif.fmsware.GifDecoder.STATUS_OK) {
                Logger.warn("Failed to decode GIF: status={}", status);
                return null;
            }

            int frameCount = decoder.getFrameCount();
            Logger.info("GIF: {} frames found", frameCount);

            // Clamp frame index to valid range
            int index = Math.min(frameIndex, frameCount - 1);

            BufferedImage frame = decoder.getFrame(index);
            Logger.info("GIF: Loaded frame {}/{}", index, frameCount);

            return frame;

        } catch (Exception e) {
            Logger.error("Failed to load GIF frame: {}", e.getMessage());
            return null;
        }
    }

    public static int getCacheSize() {
        return gpuCache.size();
    }

    public static long getDiskCacheSizeMB() {
        return getDiskCacheSize() / 1024 / 1024;
    }

    public static int getImageWidth(String url) {
        ImageEntry entry = gpuCache.get(url);
        return entry != null ? entry.width : 0;
    }

    public static int getImageHeight(String url) {
        ImageEntry entry = gpuCache.get(url);
        return entry != null ? entry.height : 0;
    }

    public static Path getCachePath() {
        return cacheDir;
    }
}