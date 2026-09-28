package com.peak885.peakybrowser4jv2.browser.image;

public final class ImageResource {

    private final String url;
    private final ImageData data;

    public ImageResource(String url, ImageData data) {
        this.url = url;
        this.data = data;
    }

    public String url() {
        return url;
    }

    public ImageData data() {
        return data;
    }
}