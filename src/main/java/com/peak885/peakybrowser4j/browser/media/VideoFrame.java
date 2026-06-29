package com.peak885.peakybrowser4j.browser.media;

import java.nio.ByteBuffer;

public record VideoFrame(
        ByteBuffer pixels,
        int width,
        int height
) {}