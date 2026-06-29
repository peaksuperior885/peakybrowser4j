package com.peak885.peakybrowser4j.browser.image;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Image cache entry - stores GPU handle + disk path
 * NO BufferedImage in memory!
 */
public class ImageEntry {
    public final String url;

    // GPU state
    public int textureId = -1;
    public boolean failed = false;

    // Metadata
    public int width = 0;
    public int height = 0;

    // Disk location (can reload if GPU evicts texture)
    public Path cachePath;

    public ByteBuffer imageBuffer = null;

    // Access tracking for GPU LRU eviction
    public long lastAccessTime = System.currentTimeMillis();

    public ImageEntry(String url) {
        this.url = url;
    }

    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    public boolean isReady() {
        return textureId != -1 && !failed;
    }
}