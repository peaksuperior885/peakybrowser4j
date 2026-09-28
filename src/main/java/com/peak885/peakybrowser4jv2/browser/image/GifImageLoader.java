package com.peak885.peakybrowser4jv2.browser.image;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.madgag.gif.fmsware.GifDecoder;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GifImageLoader {

    private static final Cache<InputStream, ImageData> memoryCache = Caffeine.newBuilder()
            .initialCapacity(20)
            .maximumSize(100)
            .expireAfterAccess(15, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private GifImageLoader() {
    }

    @NotNull
    public static ImageData load(@NotNull InputStream input) throws IOException {
        ImageData cachedData = memoryCache.getIfPresent(input);
        if (cachedData != null) {
            return cachedData;
        }

        GifDecoder decoder = new GifDecoder();
        int status = decoder.read(input);

        if (status != GifDecoder.STATUS_OK) {
            throw new IOException("Failed to decode GIF, status: " + status);
        }

        int frameCount = decoder.getFrameCount();

        if (frameCount <= 0) {
            throw new IOException("GIF contains no frames");
        }

        List<BufferedImage> frames = new ArrayList<>(frameCount);
        List<Integer> delays = new ArrayList<>(frameCount);

        for (int i = 0; i < frameCount; i++) {
            BufferedImage frame = decoder.getFrame(i);

            if (frame == null) {
                throw new IOException("GIF frame " + i + " is null");
            }

            frames.add(frame);
            delays.add(Math.max(1, decoder.getDelay(i)));
        }

        ImageData imageData = ImageData.animated(frames, delays);
        memoryCache.put(input, imageData);
        return imageData;
    }

    public static void clearCache() {
        memoryCache.invalidateAll();
    }
}