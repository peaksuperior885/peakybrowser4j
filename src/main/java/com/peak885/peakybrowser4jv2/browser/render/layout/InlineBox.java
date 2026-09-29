package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

public final class InlineBox extends Box {

    @NotNull
    public final String text;

    @Nullable
    public final String href;

    public final boolean spaceBefore;
    public final boolean forcedBreak;

    @Nullable
    private final ImageData image;

    @Nullable
    private final Box blockContent;

    float ascent;
    float descent;

    private int selectionStart = -1;
    private int selectionEnd = -1;

    // Animation tracking state for animated image frames
    private int currentFrameIndex = 0;
    private long lastFrameSwitchTime = System.currentTimeMillis();

    public InlineBox(
            @NotNull ComputedStyle style,
            @Nullable String text,
            @Nullable String href,
            boolean spaceBefore
    ) {
        super(style);

        this.text = text == null ? "" : text;
        this.href = href;
        this.spaceBefore = spaceBefore;
        this.forcedBreak = false;
        this.image = null;
        this.blockContent = null;
    }

    private InlineBox(@NotNull ComputedStyle style, boolean forcedBreak) {
        super(style);

        this.text = "";
        this.href = null;
        this.spaceBefore = false;
        this.forcedBreak = forcedBreak;
        this.image = null;
        this.blockContent = null;
    }

    private InlineBox(
            @NotNull ComputedStyle style,
            @NotNull ImageData image,
            @Nullable String href
    ) {
        super(style);

        this.text = "";
        this.href = href;
        this.spaceBefore = false;
        this.forcedBreak = false;
        this.image = image;
        this.blockContent = null;
    }

    private InlineBox(
            @NotNull ComputedStyle style,
            @NotNull Box blockContent,
            @Nullable String href
    ) {
        super(style);

        this.text = "";
        this.href = href;
        this.spaceBefore = false;
        this.forcedBreak = false;
        this.image = null;
        this.blockContent = blockContent;
    }

    @NotNull
    public static InlineBox lineBreak(@NotNull ComputedStyle style) {
        return new InlineBox(style, true);
    }

    @NotNull
    public static InlineBox image(
            @NotNull ComputedStyle style,
            @NotNull ImageData image,
            @Nullable String href
    ) {
        return new InlineBox(style, image, href);
    }

    @NotNull
    public static InlineBox inlineBlock(
            @NotNull ComputedStyle style,
            @NotNull Box blockContent,
            @Nullable String href
    ) {
        return new InlineBox(style, blockContent, href);
    }

    public boolean isImage() {
        return image != null;
    }

    public boolean isInlineBlock() {
        return blockContent != null;
    }

    @Nullable
    public ImageData image() {
        return image;
    }

    @Nullable
    public Box blockContent() {
        return blockContent;
    }

    public boolean isLink() {
        return href != null && !href.isBlank();
    }

    @Override
    public void layout(
            float availableWidth,
            float startX,
            float startY
    ) {
        x = startX;
        y = startY;

        borderBoxX = startX;
        borderBoxY = startY;

        if (!isInlineBlock() || blockContent == null) {
            return;
        }

        float contentWidth = width;

        if (contentWidth <= 0) {
            contentWidth = blockContent.getBorderBoxWidth();
        }

        if (contentWidth <= 0) {
            contentWidth = Math.max(0, availableWidth);
        }

        width = contentWidth;

        blockContent.layout(
                contentWidth,
                startX,
                startY
        );

        height = blockContent.totalHeight();

        borderBoxWidth = width;
        borderBoxHeight = height;
    }

    @Override
    public void paint(@NotNull Graphics2D g) {
        if (forcedBreak) {
            return;
        }

        if (isInlineBlock() && blockContent != null) {
            blockContent.paint(g);
            return;
        }

        if (isImage()) {
            paintImage(g);
            return;
        }

        if (text.isEmpty()) {
            return;
        }

        Font font = createFont();

        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int drawX = Math.round(x);
        int baseline = Math.round(y + fm.getAscent());

        int selectedFrom = Math.max(0, Math.min(text.length(), selectionStart));
        int selectedTo = Math.max(selectedFrom, Math.min(text.length(), selectionEnd));

        g.setColor(style.color());
        if (selectedFrom < selectedTo) {
            int selectionX = drawX + fm.stringWidth(text.substring(0, selectedFrom));
            int selectionRight = drawX + fm.stringWidth(text.substring(0, selectedTo));
            g.setColor(new Color(55, 125, 235));
            g.fillRect(selectionX, baseline - fm.getAscent(), selectionRight - selectionX, fm.getHeight());
            g.setColor(Color.WHITE);
            g.drawString(text.substring(selectedFrom, selectedTo), selectionX, baseline);
            g.setColor(style.color());
            if (selectedFrom > 0) {
                g.drawString(text.substring(0, selectedFrom), drawX, baseline);
            }
            if (selectedTo < text.length()) {
                int suffixX = drawX + fm.stringWidth(text.substring(0, selectedTo));
                g.drawString(text.substring(selectedTo), suffixX, baseline);
            }
        } else {
            g.drawString(text, drawX, baseline);
        }

        if (style.textDecorationUnderline()) {
            int underlineY = baseline + Math.max(
                    1,
                    fm.getDescent() / 2
            );

            g.drawLine(
                    drawX,
                    underlineY,
                    Math.round(x + width),
                    underlineY
            );
        }
    }

    private void paintImage(@NotNull Graphics2D g) {
        if (image == null) {
            return;
        }

        List<BufferedImage> frames = image.frames();
        if (frames.isEmpty()) {
            return;
        }

        if (image.isAnimated()) {
            List<Integer> delays = image.delays();
            long now = System.currentTimeMillis();
            int currentDelay = delays.get(currentFrameIndex);
            // GIF delay units are typically hundredths of a second (10ms)
            long delayMillis = Math.max(10L, currentDelay * 10L);

            if (now - lastFrameSwitchTime >= delayMillis) {
                currentFrameIndex = (currentFrameIndex + 1) % frames.size();
                lastFrameSwitchTime = now;
            }
        }

        BufferedImage frame = frames.get(currentFrameIndex);
        if (frame == null) {
            frame = image.firstFrame();
        }

        if (frame != null) {
            g.drawImage(
                    frame,
                    Math.round(x),
                    Math.round(y),
                    Math.max(1, Math.round(width)),
                    Math.max(1, Math.round(height)),
                    null
            );
        }
    }

    @NotNull
    Font createFont() {
        int fontStyle = Font.PLAIN;

        if (style.fontWeightBold()) {
            fontStyle |= Font.BOLD;
        }

        if (style.fontStyleItalic()) {
            fontStyle |= Font.ITALIC;
        }

        String family = style.fontFamily();

        if (family == null || family.isBlank()) {
            family = "SansSerif";
        }

        int size = Math.max(
                1,
                Math.round(style.fontSize())
        );

        Font font = new Font(
                family,
                fontStyle,
                size
        );

        if (!family.equalsIgnoreCase("SansSerif")
                && !font.getFamily().equalsIgnoreCase(family)) {

            font = new Font(
                    "SansSerif",
                    fontStyle,
                    size
            );
        }

        return font;
    }

    public boolean containsPoint(
            float px,
            float py
    ) {
        return px >= borderBoxX
                && px <= borderBoxX + borderBoxWidth
                && py >= borderBoxY
                && py <= borderBoxY + borderBoxHeight;
    }

    public void setSelection(int start, int end) {
        selectionStart = Math.max(0, Math.min(text.length(), start));
        selectionEnd = Math.max(selectionStart, Math.min(text.length(), end));
        if (selectionStart == selectionEnd) {
            selectionStart = selectionEnd = -1;
        }
    }

    public void clearSelection() {
        selectionStart = selectionEnd = -1;
    }

    public int selectionStart() {
        return selectionStart;
    }

    public int selectionEnd() {
        return selectionEnd;
    }

    public int textOffsetAt(float px) {
        FontMetrics fm = Toolkit.getDefaultToolkit().getFontMetrics(createFont());
        float relativeX = px - x;
        for (int i = 0; i < text.length(); i++) {
            int left = fm.stringWidth(text.substring(0, i));
            int right = fm.stringWidth(text.substring(0, i + 1));
            if (relativeX < (left + right) / 2f) return i;
        }
        return text.length();
    }
}
