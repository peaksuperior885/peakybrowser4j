package com.peak885.peakybrowser4j.browser.media;

public class VideoBounds {
    private float x, y, w, h;

    public VideoBounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getW() { return w; }
    public float getH() { return h; }
}