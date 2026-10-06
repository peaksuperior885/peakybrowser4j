package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import com.peak885.peakybrowser4jv2.browser.media.AudioPlayer;
import com.peak885.peakybrowser4jv2.browser.media.JcodecVideoDecoder;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.jsoup.nodes.Element;
import org.tinylog.Logger;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoBox extends Box {

    /** Target playback interval in ms (~30 fps -> 33ms per frame). */
    private static final int FRAME_DELAY_MS = 33;

    private final Element element;
    private final BrowserLoader browserLoader;

    private volatile List<BufferedImage> frames = Collections.emptyList();
    private volatile BufferedImage frame;
    private volatile String status = "Loading...";

    private long playbackStartTime = 0;
    private Timer playbackTimer;

    private final AtomicBoolean loadStarted = new AtomicBoolean(false);
    private final AtomicBoolean audioStop = new AtomicBoolean(false);

    public VideoBox(
            ComputedStyle style,
            Element element,
            BrowserLoader browserLoader
    ) {
        super(style);
        this.element = element;
        this.browserLoader = browserLoader;
        this.sourceElement = element;

        startLoad();
    }

    public Element element() {
        return element;
    }

    public BufferedImage frame() {
        return frame;
    }

    public void setFrame(BufferedImage frame) {
        this.frame = frame;
    }

    /** Stop audio and the frame timer (e.g. when navigating away). */
    public void stop() {
        audioStop.set(true);
        SwingUtilities.invokeLater(() -> {
            if (playbackTimer != null) {
                playbackTimer.stop();
                playbackTimer = null;
            }
        });
    }

    private void startLoad() {
        if (!loadStarted.compareAndSet(false, true)) {
            return;
        }

        String videoUrl = resolveVideoUrl(element);
        if (videoUrl == null || videoUrl.isBlank()) {
            status = "No source";
            return;
        }

        new Thread(() -> {
            try {
                Logger.info("[VIDEO] Fetching {}", videoUrl);

                HttpManager.HttpResponse response =
                        browserLoader.getHttpManager().get(
                                videoUrl,
                                "video/mp4,video/*,*/*;q=0.8"
                        );

                if (!response.isSuccessful()) {
                    status = "HTTP " + response.getStatusCode();
                    Logger.warn(
                            "[VIDEO] Failed to fetch {}: {} {}",
                            videoUrl,
                            response.getStatusCode(),
                            response.getStatusMessage()
                    );
                    requestRepaint();
                    return;
                }

                byte[] body = response.getBody();
                status = "Decoding...";
                requestRepaint();

                // Start audio extraction and playback immediately before heavy video decoding blocks
                startAudio(body);

                List<BufferedImage> decoded =
                        JcodecVideoDecoder.decodeFrames(body);

                if (decoded.isEmpty()) {
                    status = "No frames";
                    Logger.warn("[VIDEO] No frames decoded from {}", videoUrl);
                    requestRepaint();
                    return;
                }

                frames = decoded;
                frame = decoded.get(0);
                status = null;

                Logger.info(
                        "[VIDEO] Ready {}x{} - {} frames ({})",
                        frame.getWidth(),
                        frame.getHeight(),
                        decoded.size(),
                        videoUrl
                );

                // Start video playback timer now that frames are ready
                playbackStartTime = System.currentTimeMillis();
                startPlaybackTimer();
                requestRepaint();

            } catch (Exception e) {
                status = "Error";
                Logger.warn(e, "[VIDEO] Failed to load/decode {}", videoUrl);
                requestRepaint();
            }
        }, "video-decode").start();
    }

    private void startAudio(byte[] mp4Bytes) {
        audioStop.set(false);
        Thread t = new Thread(
                () -> AudioPlayer.playMp4Aac(mp4Bytes, audioStop),
                "mp4-audio"
        );
        t.setDaemon(true);
        t.start();
    }

    private void startPlaybackTimer() {
        SwingUtilities.invokeLater(() -> {
            if (playbackTimer != null) {
                playbackTimer.stop();
            }
            playbackTimer = new Timer(FRAME_DELAY_MS, e -> requestRepaint());
            playbackTimer.setRepeats(true);
            playbackTimer.start();
        });
    }

    private void advanceFrameIfNeeded() {
        List<BufferedImage> local = frames;
        if (local.size() <= 1 || playbackStartTime == 0) {
            return;
        }

        // Calculate exact frame index based on elapsed time to prevent drifting out of sync with audio
        long elapsed = System.currentTimeMillis() - playbackStartTime;
        int targetFrameIndex = (int) (elapsed / FRAME_DELAY_MS);

        if (targetFrameIndex >= local.size()) {
            // Loop video or clamp at the end
            playbackStartTime = System.currentTimeMillis(); // Loop back to start
            targetFrameIndex = 0;
        }

        frame = local.get(targetFrameIndex);
    }

    private static void requestRepaint() {
        SwingUtilities.invokeLater(() -> {
            for (Window w : Window.getWindows()) {
                if (w.isDisplayable()) {
                    w.repaint();
                }
            }
        });
    }

    static String resolveVideoUrl(Element element) {
        String src = element.attr("src").trim();
        if (!src.isBlank()) {
            String abs = element.absUrl("src");
            return abs.isBlank() ? src : abs;
        }

        String best = null;
        for (Element source : element.select("source")) {
            String s = source.attr("src").trim();
            if (s.isBlank()) {
                continue;
            }
            String abs = source.absUrl("src");
            String url = abs.isBlank() ? s : abs;
            String type = source.attr("type").toLowerCase();
            if (type.contains("mp4") || url.toLowerCase().contains(".mp4")) {
                return url;
            }
            if (best == null) {
                best = url;
            }
        }
        return best;
    }

    @Override
    public void layout(
            float availableWidth,
            float startX,
            float startY
    ) {
        borderBoxX = startX;
        borderBoxY = startY;

        float defaultWidth = 300f;
        float defaultHeight = 150f;

        BufferedImage first = frame;
        if (first != null) {
            defaultWidth = first.getWidth();
            defaultHeight = first.getHeight();
        }

        float contentWidth = style.width(
                defaultWidth,
                style.fontSize(),
                availableWidth
        );

        float contentHeight = style.height(
                defaultHeight,
                style.fontSize(),
                availableWidth
        );

        width = Math.max(0, contentWidth);
        height = Math.max(0, contentHeight);

        borderBoxWidth = width;
        borderBoxHeight = height;

        x = startX;
        y = startY;
    }

    @Override
    public void paint(Graphics2D g) {
        int ix = Math.round(x);
        int iy = Math.round(y);
        int iw = Math.max(1, Math.round(width));
        int ih = Math.max(1, Math.round(height));

        advanceFrameIfNeeded();

        BufferedImage current = frame;
        if (current != null) {
            g.drawImage(current, ix, iy, iw, ih, null);
            return;
        }

        g.setColor(Color.BLACK);
        g.fillRect(ix, iy, iw, ih);

        g.setColor(Color.WHITE);
        String label = status != null ? status : "Video";
        g.drawString(label, ix + 10, iy + 20);
    }
}