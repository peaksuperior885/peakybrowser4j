package com.peak885.peakybrowser4jv2.browser.media;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;
import org.tinylog.Logger;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decodes H.264 / AVC video inside MP4 (ISO BMFF / QuickTime) containers
 * into a list of {@link BufferedImage} frames using jcodec.
 * <p>
 * Audio is intentionally ignored - this is video-frame playback only.
 */
public final class JcodecVideoDecoder {

    /** Soft cap so a long clip does not exhaust heap while decoding. */
    private static final int MAX_FRAMES = 600;

    private JcodecVideoDecoder() {
    }

    /**
     * Decode every native frame from the given MP4 bytes.
     *
     * @param mp4Bytes full file contents (not a stream)
     * @return immutable list of RGB frames, empty if nothing could be decoded
     */
    public static List<BufferedImage> decodeFrames(byte[] mp4Bytes)
            throws IOException, JCodecException {

        if (mp4Bytes == null || mp4Bytes.length == 0) {
            return Collections.emptyList();
        }

        List<BufferedImage> frames = new ArrayList<>();

        SeekableByteChannel channel = null;
        try {
            channel = ByteBufferSeekableByteChannel.readFromByteBuffer(
                    ByteBuffer.wrap(mp4Bytes)
            );

            FrameGrab grab = FrameGrab.createFrameGrab(channel);

            Picture picture;
            int count = 0;
            while (count < MAX_FRAMES
                    && (picture = grab.getNativeFrame()) != null) {

                frames.add(toBufferedImage(picture));
                count++;
            }

            Logger.info(
                    "[VIDEO] Decoded {} frame(s) from {} byte MP4",
                    frames.size(),
                    mp4Bytes.length
            );

        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }

        return Collections.unmodifiableList(frames);
    }

    /**
     * Convert a jcodec {@link Picture} (typically YUV420 / YUV420J) to a
     * {@link BufferedImage} of type {@code TYPE_3BYTE_BGR}.
     * <p>
     * Matches upstream jcodec AWTUtil:
     * <ul>
     *   <li>transform source color space to RGB</li>
     *   <li>jcodec stores samples as signed bytes shifted by -128
     *       (range {@code [-128, 127]} maps to {@code [0, 255]})</li>
     *   <li>pack as B,G,R for {@code TYPE_3BYTE_BGR}, adding +128 back</li>
     * </ul>
     */
    public static BufferedImage toBufferedImage(Picture src) {
        if (src == null) {
            return null;
        }

        Picture rgb = src;
        if (src.getColor() != ColorSpace.RGB) {
            Transform transform =
                    ColorUtil.getTransform(src.getColor(), ColorSpace.RGB);
            if (transform == null) {
                throw new IllegalStateException(
                        "No color transform from " + src.getColor() + " to RGB"
                );
            }
            rgb = Picture.create(
                    src.getWidth(),
                    src.getHeight(),
                    ColorSpace.RGB
            );
            transform.transform(src, rgb);
        }

        int width = rgb.getCroppedWidth();
        int height = rgb.getCroppedHeight();
        int cropX = rgb.getStartX();
        int cropY = rgb.getStartY();
        int stridePixels = rgb.getWidth();

        BufferedImage bi = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_3BYTE_BGR
        );

        byte[] dst = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData();
        byte[] srcData = rgb.getPlaneData(0);

        // Upstream AWTUtil: unshift (+128) and swap R/B for BGR order.
        int dstOff = 0;
        for (int y = 0; y < height; y++) {
            int srcOff = ((cropY + y) * stridePixels + cropX) * 3;
            for (int x = 0; x < width; x++) {
                // src is R,G,B in shifted signed form
                int r = srcData[srcOff++] + 128;
                int g = srcData[srcOff++] + 128;
                int b = srcData[srcOff++] + 128;
                dst[dstOff++] = (byte) b;
                dst[dstOff++] = (byte) g;
                dst[dstOff++] = (byte) r;
            }
        }

        return bi;
    }
}