package com.peak885.peakybrowser4j.browser.media;

public class VideoState {
    private int nvgImageHandle = -1;
    public int textureId; // The OpenGL texture handle
    public int width, height;

    public int getNvgImageHandle() {
        return nvgImageHandle;
    }

    public void setNvgImageHandle(int handle) {
        this.nvgImageHandle = handle;
    }

    public VideoState(int textureId, int w, int h) {
        this.textureId = textureId;
        this.width = w;
        this.height = h;
    }
}