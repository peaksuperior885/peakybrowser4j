package com.peak885.peakybrowser4j.browser.media;

import org.lwjgl.system.MemoryUtil;
import org.tinylog.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.nanovg.NanoVG.*;

public class VideoTexture {

    private int nvgImageHandle = -1;
    private int currentW = 0;
    private int currentH = 0;

    private final AtomicReference<ByteBuffer> stagingBuffer = new AtomicReference<>();

    public int getCurrentW() { return currentW; }
    public int getCurrentH() { return currentH; }

    public void setFrameData(ByteBuffer newBuffer, int width, int height) {
        this.currentW = width;
        this.currentH = height;

        ByteBuffer old = stagingBuffer.getAndSet(newBuffer);
        if (old != null) {
            MemoryUtil.memFree(old);
        }
    }

    /**
     * Returns true if upload succeeded
     */
    public boolean uploadToGPU(long vg, int width, int height) {
        ByteBuffer buffer = stagingBuffer.getAndSet(null);
        if (buffer == null) {
            return nvgImageHandle != -1; // keep previous frame if no new one
        }

        // Delete old image if size changed
        if (nvgImageHandle != -1 && (currentW != width || currentH != height)) {
            nvgDeleteImage(vg, nvgImageHandle);
            nvgImageHandle = -1;
        }

        try {
            if (nvgImageHandle == -1) {
                nvgImageHandle = nvgCreateImageRGBA(vg, width, height, 0, buffer);
                if (nvgImageHandle == -1) {
                    Logger.error("Failed to create NanoVG image for video");
                    return false;
                }
                this.currentW = width;
                this.currentH = height;
                Logger.info("Video texture created: {}x{}", width, height);
            } else {
                nvgUpdateImage(vg, nvgImageHandle, buffer);
            }
            return true;
        } catch (Exception e) {
            Logger.error(e, "Failed to upload video frame to GPU");
            return false;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public void dispose(long vg) {
        if (nvgImageHandle != -1) {
            nvgDeleteImage(vg, nvgImageHandle);
            nvgImageHandle = -1;
        }

        ByteBuffer old = stagingBuffer.getAndSet(null);
        if (old != null) {
            MemoryUtil.memFree(old);
        }
    }

    public int getNvgImageHandle() {
        return nvgImageHandle;
    }
}