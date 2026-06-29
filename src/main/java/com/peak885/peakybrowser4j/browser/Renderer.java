package com.peak885.peakybrowser4j.browser;

import com.peak885.peakybrowser4j.browser.image.*;
import com.peak885.peakybrowser4j.browser.media.*;
import com.peak885.peakybrowser4j.browser.util.ColorParser;
import com.peak885.peakybrowser4j.browser.css.computing.ComputedStyle;
import org.bytedeco.javacv.Frame;
import org.bytedeco.librealsense.frame;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryUtil;
import org.tinylog.Logger;

import static org.lwjgl.nanovg.NanoVG.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class Renderer {
    private final long vg;
    private final PageLoader loader;
    private VideoState videoState;
    public float scrollY;
    public boolean hoveringLink = false;

    private final NVGColor tmpColor = NVGColor.create();
    private final NVGColor highlightColor = NVGColor.create().r(0.2f).g(0.5f).b(0.8f).a(0.3f);
    private final NVGColor paintColor = NVGColor.create();
    public final java.util.Map<Node, VideoTexture> activeVideoPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    private final long glfwWindowHandle;
    private static final ConcurrentHashMap<String, AtomicReference<ByteBuffer>> latestFrames = new ConcurrentHashMap<>();
    private boolean scrubbing = false;
    private Node scrubbingNode = null;
    private boolean loggedRootOnce = false;

    public Renderer(long vg, PageLoader loader, long glfwWindowHandle) {
        this.vg = vg;
        this.loader = loader;
        this.glfwWindowHandle = glfwWindowHandle;
    }

    public void render(Node node, float mouseX, float mouseY, float viewportWidth, float viewportHeight) {

        if (node == null) {
            Logger.error("render() called with NULL node!");
            return;
        }

        if (node.parent == null && !loggedRootOnce) {
            Logger.info("RENDER ROOT: tag={}, w={}, h={}, children={}, bg={}, computed={}",
                    node.tag, node.width, node.height, node.children.size(),
                    node.computedStyle != null ? Arrays.toString(node.computedStyle.backgroundColor) : "NO_STYLE",
                    node.computedStyle != null ? "YES" : "NO");
            loggedRootOnce = true;
        }

        if (node.computedStyle == null) {
            Logger.warn("Node {} has null ComputedStyle!", node.tag);
            node.computedStyle = new ComputedStyle();
        }

        if (node.width <= 0 || node.height <= 0) {
            if (node.parent == null) {
                Logger.error("ROOT NODE HAS INVALID DIMENSIONS: {}x{}", node.width, node.height);
            }
            node.children.forEach(c -> render(c, mouseX, mouseY, viewportWidth, viewportHeight));
            return;
        }

        float screenY = node.y - scrollY;

        if (screenY + node.height < 0 || screenY > viewportHeight) {
            node.children.forEach(c -> render(c, mouseX, mouseY, viewportWidth, viewportHeight));
            return;
        }

        boolean isHovering = isMouseOverLink(node, mouseX, mouseY);
        if (isHovering) {
            this.hoveringLink = true;
            paintHighlight(node);
        }

        paintBackground(node);
        node.children.forEach(child -> render(child, mouseX, mouseY, viewportWidth, viewportHeight));

        if ("button".equals(node.tag)) {
            paintButton(node);
        }

        if (node.type == Node.Type.TEXT) {
            paintText(node);
        } else if (node.type == Node.Type.IMAGE) {
            paintImage(node);
        }

        if ("video".equals(node.tag)) {
            renderVideo(node, mouseX, mouseY);
        }
    }

    private void renderVideo(Node node, float mouseX, float mouseY) {
        VideoTexture texture = activeVideoPlayers.computeIfAbsent(node, n -> {
            VideoEngine.startVideo(n.videoUrl);
            return new VideoTexture();
        });

        VideoFrame frame = VideoEngine.grabLatestFrame(node.videoUrl);

        if (frame != null) {
            System.out.printf("[VIDEO] Frame received: %dx%d | buffer=%d bytes%n",
                    frame.width(), frame.height(), frame.pixels().remaining());

            texture.setFrameData(frame.pixels(), frame.width(), frame.height());
            boolean success = texture.uploadToGPU(vg, frame.width(), frame.height());

            System.out.println("[VIDEO] Upload success = " + success +
                    " | NVG Handle = " + texture.getNvgImageHandle());
        } else {
           //  System.out.println("[VIDEO] No new frame this frame"); // too spammy
        }

        if (texture.getNvgImageHandle() == -1) {
            drawVideoPlaceholder(node);
            drawVideoControls(node, node.x, node.y - scrollY, node.width, node.height, mouseX, mouseY);
            return;
        }

        // Render the actual video
        float aspect = (float) texture.getCurrentW() / texture.getCurrentH();
        float drawW = node.width;
        float drawH = drawW / aspect;
        if (drawH > node.height) {
            drawH = node.height;
            drawW = drawH * aspect;
        }

        float drawX = node.x + (node.width - drawW) / 2f;
        float drawY = (node.y - scrollY) + (node.height - drawH) / 2f;

        try (NVGPaint paint = NVGPaint.create()) {
            nvgImagePattern(vg, drawX, drawY, drawW, drawH, 0, texture.getNvgImageHandle(), 1.0f, paint);
            nvgBeginPath(vg);
            nvgRect(vg, drawX, drawY, drawW, drawH);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }

        drawVideoControls(node, drawX, drawY, drawW, drawH, mouseX, mouseY);
    }

    private void drawVideoPlaceholder(Node node) {
        float screenY = node.y - scrollY;
        // Dark background
        nvgBeginPath(vg);
        nvgRect(vg, node.x, screenY, node.width, node.height);
        nvgFillColor(vg, color(0.1f, 0.1f, 0.1f, 1f));
        nvgFill(vg);

        // "Loading..." text
        nvgFontSize(vg, 18);
        nvgFillColor(vg, color(1, 1, 1, 0.7f));
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgText(vg, node.x + node.width/2f, screenY + node.height/2f, "Loading video...");
    }

    public static ByteBuffer convertFrameToRGBA(Frame frame) {
        if (frame.image == null || frame.image[0] == null) return null;

        int width = frame.imageWidth;
        int height = frame.imageHeight;
        ByteBuffer src = (ByteBuffer) frame.image[0];

        if (width <= 0 || height <= 0) return null;

        ByteBuffer dest = MemoryUtil.memAlloc(width * height * 4);
        src.rewind();

        // Try to handle different formats safely
        int stride = frame.imageStride > 0 ? frame.imageStride : width * 3; // assume RGB24 or BGR24

        for (int y = 0; y < height; y++) {
            src.position(y * stride);
            for (int x = 0; x < width; x++) {
                byte b1 = src.get();
                byte b2 = src.get();
                byte b3 = src.get();

                // Assume BGR or RGB - try both common cases
                dest.put(b3).put(b2).put(b1).put((byte) 255);   // BGR -> RGBA
            }
        }

        dest.flip();
        return dest;
    }

    private void drawVideoControls(Node node, float x, float y, float w, float h, float mouseX, float mouseY) {
        final float CONTROL_BAR_HEIGHT = 40f;
        final float BAR_Y = y + h - CONTROL_BAR_HEIGHT;
        final float MARGIN = 10f;
        final float BUTTON_SIZE = 30f;

        // 1. Draw Background
        drawRect(x, BAR_Y, w, CONTROL_BAR_HEIGHT, new float[]{0.1f, 0.1f, 0.1f, 0.9f});

        // 2. Play/Pause Button
        boolean isPlaying = VideoEngine.isPlaying(node.videoUrl);
        drawPlayButton(x + MARGIN, BAR_Y + (CONTROL_BAR_HEIGHT - BUTTON_SIZE) / 2f, BUTTON_SIZE, isPlaying);

        // 3. Progress Bar Calculations
        float progressStartX = x + MARGIN + BUTTON_SIZE + 10f;
        float progressWidth = w - (MARGIN * 2 + BUTTON_SIZE + 20f + 100f); // Reserve space for time text
        float progressY = BAR_Y + CONTROL_BAR_HEIGHT / 2f;

        float duration = VideoEngine.getDuration(node.videoUrl);
        float currentTime = VideoEngine.getCurrentTime(node.videoUrl);
        float progress = (duration > 0) ? Math.max(0f, Math.min(1f, currentTime / duration)) : 0f;

        // 4. Render Progress Bar components
        renderProgressBar(progressStartX, progressY, progressWidth, progress);

        // 5. Time Text
        String timeStr = formatTime(currentTime) + " / " + formatTime(duration);
        nvgFontSize(vg, 12);
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, color(1, 1, 1, 1));
        nvgText(vg, progressStartX + progressWidth + 10f, BAR_Y + CONTROL_BAR_HEIGHT / 2f, timeStr);
    }

    private void renderProgressBar(float x, float y, float width, float progress) {
        // Background Track
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y - 2, width, 4, 2);
        nvgFillColor(vg, color(0.3f, 0.3f, 0.3f, 1f));
        nvgFill(vg);

        // Progress Fill
        float fillWidth = width * progress;
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y - 2, fillWidth, 4, 2);
        nvgFillColor(vg, color(0.26f, 0.52f, 0.96f, 1f));
        nvgFill(vg);

        // Scrubber Handle
        nvgBeginPath(vg);
        nvgCircle(vg, x + fillWidth, y, 6);
        nvgFillColor(vg, color(1f, 1f, 1f, 1f));
        nvgFill(vg);
    }

    public void handleVideoClick(Node node, float mouseX, float mouseY, float scrollY) {
        if (!"video".equals(node.tag)) return;

        VideoTexture texture = activeVideoPlayers.get(node);
        VideoBounds bounds = getVideoBounds(node, texture);

        boolean insideVideo =
                mouseX >= bounds.getX() &&
                        mouseX <= bounds.getX() + bounds.getW() &&
                        mouseY >= bounds.getY() &&
                        mouseY <= bounds.getY() + bounds.getH();

        if (!insideVideo) return;

        final float CONTROL_BAR_HEIGHT = 40f;
        float controlTop = bounds.getY() + bounds.getH() - CONTROL_BAR_HEIGHT;

        boolean insideControls = mouseY >= controlTop;

        if (insideControls) {
            scrubbing = true;
            scrubbingNode = node;
            handleControlBarClick(node, mouseX, mouseY, bounds, controlTop);
        } else {
            VideoEngine.togglePlayPause(node.videoUrl);
        }
    }

    private VideoBounds getVideoBounds(Node node, VideoTexture texture) {
        if (texture == null || texture.getCurrentW() <= 0 || texture.getCurrentH() <= 0) {
            // Fallback: use full node
            float screenY = node.y - scrollY;
            return new VideoBounds(node.x, screenY, node.width, node.height);
        }

        float aspect = (float) texture.getCurrentW() / texture.getCurrentH();
        float drawW = node.width;
        float drawH = drawW / aspect;

        if (drawH > node.height) {
            drawH = node.height;
            drawW = drawH * aspect;
        }

        float drawX = node.x + (node.width - drawW) / 2f;
        float drawY = (node.y - scrollY) + (node.height - drawH) / 2f;

        return new VideoBounds(drawX, drawY, drawW, drawH);
    }

    private void handleControlBarClick(Node node, float mouseX, float mouseY,
                                       VideoBounds bounds, float barY) {
        float margin = 10f;
        float buttonSize = 30f;

        float buttonX = bounds.getX() + margin;
        float buttonY = barY + (40f - buttonSize) / 2f;

        // Play/Pause
        if (mouseX >= buttonX && mouseX <= buttonX + buttonSize &&
                mouseY >= buttonY && mouseY <= buttonY + buttonSize) {
            VideoEngine.togglePlayPause(node.videoUrl);
            return;
        }

        // Progress bar
        float progressStartX = bounds.getX() + margin + buttonSize + 10f;
        float progressWidth = bounds.getW() - (margin * 2) - buttonSize - 20f;

        if (mouseX >= progressStartX && mouseX <= progressStartX + progressWidth) {
            float duration = VideoEngine.getDuration(node.videoUrl);
            if (duration > 0) {
                float clickProgress = (mouseX - progressStartX) / progressWidth;
                clickProgress = Math.max(0, Math.min(1, clickProgress));
                VideoEngine.seek(node.videoUrl, clickProgress * duration);
            }
        }
    }

    private void handleControlBarInteraction(Node node, float mouseX, float barY) {
        float controlBarHeight = 40f;
        float margin = 10f;
        float buttonSize = 30f;

        float progressStartX = node.x + margin + buttonSize + 10f;
        float progressWidth = node.width - (margin * 2) - buttonSize - 20f;

        // Seek logic
        if (mouseX >= progressStartX && mouseX <= progressStartX + progressWidth) {
            float duration = VideoEngine.getDuration(node.videoUrl);
            float clickProgress = (float)(mouseX - progressStartX) / progressWidth;
            clickProgress = Math.max(0, Math.min(1, clickProgress));
            float seekTime = clickProgress * duration;
            VideoEngine.seek(node.videoUrl, seekTime);
        }
    }

    private void drawPlayButton(float x, float y, float size, boolean isPlaying) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, size, size);
        nvgFillColor(vg, color(0.2f, 0.2f, 0.2f, 1f));
        nvgFill(vg);

        if (isPlaying) {
            // PAUSE ICON (two bars)
            nvgBeginPath(vg);
            nvgRect(vg, x + 8, y + 6, 3, 18);
            nvgFillColor(vg, color(1, 1, 1, 1));
            nvgFill(vg);

            nvgBeginPath(vg);
            nvgRect(vg, x + 19, y + 6, 3, 18);
            nvgFillColor(vg, color(1, 1, 1, 1));
            nvgFill(vg);
        } else {
            // PLAY ICON (triangle)
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + 8, y + 6);
            nvgLineTo(vg, x + 8, y + 24);
            nvgLineTo(vg, x + 22, y + 15);
            nvgClosePath(vg);
            nvgFillColor(vg, color(1, 1, 1, 1));
            nvgFill(vg);
        }
    }

    private String formatTime(float seconds) {
        if (Float.isNaN(seconds) || Float.isInfinite(seconds)) return "0:00";
        int mins = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        return String.format("%d:%02d", mins, secs);
    }

    private void paintButton(Node node) {
        float radius = 3.0f;

        try (NVGPaint paint = NVGPaint.create()) {

            NVGColor colorStart = NVGColor.create().r(0.9f).g(0.9f).b(0.9f).a(1.0f);
            NVGColor colorEnd = NVGColor.create().r(0.8f).g(0.8f).b(0.8f).a(1.0f);

            nvgLinearGradient(vg, node.x, node.y, node.x, node.y + node.height, colorStart, colorEnd, paint);

            nvgBeginPath(vg);
            nvgRoundedRect(vg, node.x, node.y, node.width, node.height, radius);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }

        nvgFillColor(vg, color(0, 0, 0, 1)); // Assuming you have your existing color() helper
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgText(vg, node.x + (node.width / 2f), node.y + (node.height / 2f), node.text != null ? node.text : "");
    }

    // Inside Renderer.java
    public boolean isMouseOverInteractive(Node node, float mouseX, float mouseY, float scrollY) {
        if (isMouseOverLink(node, mouseX, mouseY)) return true;

        if ("video".equals(node.tag)) {
            VideoTexture texture = activeVideoPlayers.get(node);
            VideoBounds bounds = getVideoBounds(node, texture);

            float controlTop = bounds.getY() + bounds.getH() - 40f;

            if (mouseY >= controlTop && mouseY <= bounds.getY() + bounds.getH()) {
                return true;
            }
        }

        for (Node child : node.children) {
            if (isMouseOverInteractive(child, mouseX, mouseY, scrollY)) return true;
        }
        return false;
    }

    private boolean isMouseOverLink(Node node, float mouseX, float mouseY) {
        if (!"a".equals(node.tag)) return false;
        float screenY = node.y - scrollY;
        return mouseX >= node.x && mouseX <= node.x + node.width &&
                mouseY >= screenY && mouseY <= screenY + node.height;
    }

    private void paintHighlight(Node node) {
        float screenY = node.y - scrollY;
        nvgBeginPath(vg);
        nvgRect(vg, node.x, screenY, node.width, node.height);
        nvgFillColor(vg, highlightColor);
        nvgFill(vg);
    }

    private void paintBackground(Node node) {
        if (node.type == Node.Type.TEXT) return;
        if (node.computedStyle == null) return;

        float screenY = (node.parent == null) ? node.y : node.y - scrollY;

        nvgBeginPath(vg);
        nvgRoundedRect(vg, node.x, screenY, node.width, node.height, node.borderRadius);

        if (node.computedStyle.backgroundColor != null) {
            float[] bg = node.computedStyle.backgroundColor;
            nvgFillColor(vg, color(bg[0], bg[1], bg[2], bg[3]));
            nvgFill(vg);
        }

        if (node.borderWidth > 0) {
            nvgStrokeWidth(vg, node.borderWidth);
            nvgStrokeColor(vg, node.borderColor);
            nvgStroke(vg);
        }
    }

    private void paintText(Node node) {
        if (node.text == null || node.text.isEmpty()) return;

        float screenY = node.y - scrollY;

        Node styleNode = (node.parent != null) ? node.parent : node;

        ComputedStyle style = styleNode.computedStyle;

        float fontSize = style.fontSize;
        if (fontSize <= 0 || fontSize > 200) {
            fontSize = 16f;
        }

        float[] color = style.textColor;
        if (color == null || color.length < 4) {
            color = new float[]{0f, 0f, 0f, 1f};
        }

        nvgFontSize(vg, fontSize);
        nvgFontFace(vg, FontManager.SEGOE_UI);
        nvgFillColor(vg, color(color[0], color[1], color[2], color[3]));


//        Logger.info(
//                "TEXT='{}' tag={} size={} color={}",
//                node.text,
//                node.parent != null ? node.parent.tag : "none",
//                node.computedStyle.fontSize,
//                Arrays.toString(node.computedStyle.textColor)
//        );
        nvgText(vg, node.x, screenY + fontSize * 0.8f, node.text);
    }

    private void drawRect(float x, float y, float w, float h, float[] rgba) {
        if (w <= 0 || h <= 0) return;
        if (rgba == null || rgba.length < 4) return;

        nvgBeginPath(vg);
        nvgRect(vg, x, y, w, h);
        nvgFillColor(vg, color(rgba[0], rgba[1], rgba[2], rgba[3]));
        nvgFill(vg);
    }

    private void paintImage(Node node) {
        if (node.imageUrl == null) return;

        ImageSystem.requestLoad(node.imageUrl);

        int imageHandle = ImageSystem.getTexture(vg, node.imageUrl);

        float screenY = node.y - scrollY;

        int imgWidth = ImageSystem.getImageWidth(node.imageUrl);
        int imgHeight = ImageSystem.getImageHeight(node.imageUrl);

        // Fallback to node size if image dimensions unknown
        if (imgWidth <= 0) imgWidth = (int) node.width;
        if (imgHeight <= 0) imgHeight = (int) node.height;

        float drawX = node.x;
        float drawY = screenY;
        float drawW = node.width;
        float drawH = node.height;

        if (node.imageScaleMode == ImageScaleMode.CONTAIN) {
            float imgAspect = (float) imgWidth / imgHeight;
            float nodeAspect = node.width / node.height;

            if (imgAspect > nodeAspect) {
                drawW = node.width;
                drawH = node.width / imgAspect;
            } else {
                drawH = node.height;
                drawW = node.height * imgAspect;
            }

            drawX = node.x + (node.width - drawW) / 2f;
            drawY = screenY + (node.height - drawH) / 2f;
        }
        else if (node.imageScaleMode == ImageScaleMode.COVER) {
            float imgAspect = (float) imgWidth / imgHeight;
            float nodeAspect = node.width / node.height;

            if (imgAspect > nodeAspect) {
                drawH = node.height;
                drawW = node.height * imgAspect;
            } else {
                drawW = node.width;
                drawH = node.width / imgAspect;
            }

            drawX = node.x + (node.width - drawW) / 2f;
            drawY = screenY + (node.height - drawH) / 2f;
        }

        nvgScissor(vg, node.x, screenY, node.width, node.height);

        nvgBeginPath(vg);
        nvgRect(vg, drawX, drawY, drawW, drawH);

        if (imageHandle != -1) {
            try (NVGPaint paint = NVGPaint.create()) {
                nvgImagePattern(vg, drawX, drawY, drawW, drawH, 0, imageHandle, 1.0f, paint);
                nvgFillPaint(vg, paint);
            }
        } else {
            nvgFillColor(vg, color(0.85f, 0.2f, 0.2f, 1f));
        }

        nvgFill(vg);
        nvgResetScissor(vg);
    }

    private NVGColor color(float r, float g, float b, float a) {
        return paintColor.r(Math.max(0, Math.min(1, r)))
                .g(Math.max(0, Math.min(1, g)))
                .b(Math.max(0, Math.min(1, b)))
                .a(Math.max(0, Math.min(1, a)));
    }
}