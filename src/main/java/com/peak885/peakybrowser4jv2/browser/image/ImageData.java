package com.peak885.peakybrowser4jv2.browser.image;

import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.util.List;

public final class ImageData {

    private final List<BufferedImage> frames;
    private final List<Integer> delays;

    private ImageData(@NotNull List<BufferedImage> frames, @NotNull List<Integer> delays) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("Image must contain at least one frame");
        }
        if (delays.size() != frames.size()) {
            throw new IllegalArgumentException("Frame/delay count mismatch");
        }
        this.frames = List.copyOf(frames);
        this.delays = List.copyOf(delays);
    }

    @NotNull
    public static ImageData staticImage(@NotNull BufferedImage image) {
        return new ImageData(List.of(image), List.of(0));
    }

    @NotNull
    public static ImageData animated(@NotNull List<BufferedImage> frames, @NotNull List<Integer> delays) {
        return new ImageData(frames, delays);
    }

    @NotNull
    public List<BufferedImage> frames() {
        return frames;
    }

    @NotNull
    public List<Integer> delays() {
        return delays;
    }

    public boolean isAnimated() {
        return frames.size() > 1;
    }

    @NotNull
    public BufferedImage firstFrame() {
        return frames.get(0);
    }

    public int intrinsicWidth() {
        return firstFrame().getWidth();
    }

    public int intrinsicHeight() {
        return firstFrame().getHeight();
    }
}