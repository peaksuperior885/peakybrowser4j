package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

public final class InlineFlowBox extends Box {

    private final List<InlineBox> tokens;

    public InlineFlowBox(
            ComputedStyle style,
            List<InlineBox> tokens
    ) {
        super(style);

        this.tokens = List.copyOf(tokens);
        this.children.addAll(this.tokens);
    }

    @Override
    public void layout(
            float availableWidth,
            float startX,
            float startY
    ) {
        x = startX;
        y = startY;

        width = Math.max(0, availableWidth);

        borderBoxX = x;
        borderBoxY = y;
        borderBoxWidth = width;

        if (tokens.isEmpty()) {
            height = 0;
            borderBoxHeight = 0;
            return;
        }

        final float contentLeft = x;
        final float contentRight = x + width;

        float lineTop = y;
        float cursorX = contentLeft;

        float lineAscent = 0;
        float lineDescent = 0;
        float requestedLineHeight = 0;

        List<InlineBox> currentLine = new ArrayList<>();

        for (InlineBox token : tokens) {

            if (token.forcedBreak) {
                lineTop = closeLine(
                        currentLine,
                        lineTop,
                        lineAscent,
                        lineDescent,
                        requestedLineHeight
                );

                currentLine.clear();

                cursorX = contentLeft;
                lineAscent = 0;
                lineDescent = 0;
                requestedLineHeight = 0;

                continue;
            }

            float tokenWidth;
            float tokenHeight;
            float tokenAscent;
            float tokenDescent;

            float spaceWidth = 0;

            /*
             * ------------------------------------------------------------
             * INLINE BLOCK
             * ------------------------------------------------------------
             */
            if (token.isInlineBlock()) {

                Box child = token.blockContent();

                if (child == null) {
                    continue;
                }

                float cssWidth = resolveCssWidth(token, width);
                float cssHeight = resolveCssHeight(token, width);

                float layoutWidth = cssWidth > 0
                        ? cssWidth
                        : width;

                child.layout(
                        layoutWidth,
                        0,
                        0
                );

                tokenWidth = cssWidth > 0
                        ? cssWidth
                        : child.getBorderBoxWidth();

                if (tokenWidth <= 0) {
                    tokenWidth = layoutWidth;
                }

                float naturalHeight = child.totalHeight();

                tokenHeight = cssHeight > 0
                        ? Math.max(cssHeight, naturalHeight)
                        : naturalHeight;

                tokenAscent = tokenHeight;
                tokenDescent = 0;
            }

            /*
             * ------------------------------------------------------------
             * IMAGE
             * ------------------------------------------------------------
             */
            else if (token.isImage()) {

                if (token.image() == null) {
                    continue;
                }

                float intrinsicWidth =
                        token.image().intrinsicWidth();

                float intrinsicHeight =
                        token.image().intrinsicHeight();

                if (intrinsicWidth <= 0
                        || intrinsicHeight <= 0) {
                    continue;
                }

                float cssWidth = token.style.width(
                        -1f,
                        token.style.fontSize(),
                        width
                );

                float cssHeight = token.style.height(
                        -1f,
                        token.style.fontSize(),
                        width
                );

                float maxWidth = token.style.maxWidth(
                        Float.MAX_VALUE,
                        token.style.fontSize(),
                        width
                );

                float maxHeight = token.style.maxHeight(
                        Float.MAX_VALUE,
                        token.style.fontSize(),
                        width
                );

                if (cssWidth > 0 && cssHeight > 0) {

                    tokenWidth = cssWidth;
                    tokenHeight = cssHeight;

                } else if (cssWidth > 0) {

                    tokenWidth = cssWidth;
                    tokenHeight =
                            intrinsicHeight
                                    * (cssWidth / intrinsicWidth);

                } else if (cssHeight > 0) {

                    tokenHeight = cssHeight;
                    tokenWidth =
                            intrinsicWidth
                                    * (cssHeight / intrinsicHeight);

                } else {

                    tokenWidth = intrinsicWidth;
                    tokenHeight = intrinsicHeight;
                }

                if (tokenWidth > maxWidth) {
                    float scale = maxWidth / tokenWidth;

                    tokenWidth = maxWidth;
                    tokenHeight *= scale;
                }

                if (tokenHeight > maxHeight) {
                    float scale = maxHeight / tokenHeight;

                    tokenHeight = maxHeight;
                    tokenWidth *= scale;
                }

                /*
                 * Replaced elements cannot exceed the
                 * inline formatting area's width.
                 */
                if (width > 0 && tokenWidth > width) {
                    float scale = width / tokenWidth;

                    tokenWidth = width;
                    tokenHeight *= scale;
                }

                tokenAscent = tokenHeight;
                tokenDescent = 0;
            }

            /*
             * ------------------------------------------------------------
             * TEXT
             * ------------------------------------------------------------
             */
            else {

                FontMetrics fm =
                        Toolkit.getDefaultToolkit()
                                .getFontMetrics(
                                        token.createFont()
                                );

                tokenWidth =
                        fm.stringWidth(token.text);

                tokenHeight =
                        fm.getHeight();

                tokenAscent =
                        fm.getAscent();

                tokenDescent =
                        fm.getDescent();

                spaceWidth =
                        fm.stringWidth(" ");
            }

            boolean startOfLine =
                    currentLine.isEmpty();

            float spacing =
                    startOfLine
                            ? 0
                            : token.spaceBefore
                              ? spaceWidth
                              : 0;

            float requiredWidth =
                    spacing + tokenWidth;

            /*
             * ------------------------------------------------------------
             * WRAPPING
             * ------------------------------------------------------------
             */
            if (!startOfLine
                    && cursorX + requiredWidth > contentRight) {

                lineTop = closeLine(
                        currentLine,
                        lineTop,
                        lineAscent,
                        lineDescent,
                        requestedLineHeight
                );

                currentLine.clear();

                cursorX = contentLeft;

                lineAscent = 0;
                lineDescent = 0;
                requestedLineHeight = 0;

                spacing = 0;
            }

            float tokenX = cursorX + spacing;

            token.x = tokenX;
            token.borderBoxX = tokenX;

            token.width =
                    Math.max(0, tokenWidth);

            token.borderBoxWidth =
                    token.width;

            token.height =
                    Math.max(0, tokenHeight);

            token.borderBoxHeight =
                    token.height;

            token.ascent =
                    Math.max(0, tokenAscent);

            token.descent =
                    Math.max(0, tokenDescent);

            cursorX =
                    tokenX + token.width;

            lineAscent =
                    Math.max(
                            lineAscent,
                            token.ascent
                    );

            lineDescent =
                    Math.max(
                            lineDescent,
                            token.descent
                    );

            float lineHeight =
                    token.style.lineHeight();

            if (lineHeight > 0) {
                requestedLineHeight =
                        Math.max(
                                requestedLineHeight,
                                lineHeight
                        );
            }

            currentLine.add(token);
        }

        if (!currentLine.isEmpty()) {
            lineTop = closeLine(
                    currentLine,
                    lineTop,
                    lineAscent,
                    lineDescent,
                    requestedLineHeight
            );
        }

        height =
                Math.max(
                        0,
                        lineTop - startY
                );

        borderBoxHeight = height;
    }

    private float resolveCssWidth(
            InlineBox token,
            float availableWidth
    ) {
        String value = token.style.get("width");

        if (value == null
                || value.isBlank()
                || "auto".equalsIgnoreCase(value.trim())) {
            return -1;
        }

        return token.style.width(
                -1f,
                token.style.fontSize(),
                availableWidth
        );
    }

    private float resolveCssHeight(
            InlineBox token,
            float availableWidth
    ) {
        String value = token.style.get("height");

        if (value == null
                || value.isBlank()
                || "auto".equalsIgnoreCase(value.trim())) {
            return -1;
        }

        return token.style.height(
                -1f,
                token.style.fontSize(),
                availableWidth
        );
    }

    private float closeLine(
            List<InlineBox> line,
            float lineTop,
            float ascent,
            float descent,
            float requestedLineHeight
    ) {
        if (line.isEmpty()) {
            return lineTop;
        }

        float naturalHeight =
                ascent + descent;

        if (naturalHeight <= 0) {
            naturalHeight = 1;
            ascent = 1;
            descent = 0;
        }

        float actualLineHeight =
                Math.max(
                        naturalHeight,
                        requestedLineHeight
                );

        float leading =
                actualLineHeight - naturalHeight;

        float baseline =
                lineTop
                        + leading / 2f
                        + ascent;

        /*
         * ------------------------------------------------------------
         * FIND THE ACTUAL LINE BOUNDS
         * ------------------------------------------------------------
         */

        float lineLeft = Float.MAX_VALUE;
        float lineRight = -Float.MAX_VALUE;

        for (InlineBox token : line) {
            lineLeft =
                    Math.min(
                            lineLeft,
                            token.x
                    );

            lineRight =
                    Math.max(
                            lineRight,
                            token.x + token.width
                    );
        }

        if (lineLeft == Float.MAX_VALUE) {
            return lineTop + actualLineHeight;
        }

        float lineWidth =
                lineRight - lineLeft;

        float freeSpace =
                Math.max(
                        0,
                        width - lineWidth
                );

        /*
         * ------------------------------------------------------------
         * TEXT ALIGNMENT
         * ------------------------------------------------------------
         *
         * Important:
         *
         * The tokens are already positioned relative to the
         * flow's X coordinate. We only shift the finished line.
         *
         * This means wrapping happens BEFORE alignment, which is
         * what we want.
         */

        String textAlign =
                style.get("text-align");

        if (textAlign == null
                || textAlign.isBlank()) {
            textAlign = "left";
        }

        textAlign =
                textAlign.trim().toLowerCase();

        float offset;

        switch (textAlign) {
            case "center" -> {
                offset = freeSpace / 2f;
            }

            case "right", "end" -> {
                offset = freeSpace;
            }

            case "left", "start" -> {
                offset = 0;
            }

            default -> {
                offset = 0;
            }
        }

        /*
         * ------------------------------------------------------------
         * APPLY X/Y POSITION
         * ------------------------------------------------------------
         */

        for (InlineBox token : line) {

            token.x += offset;
            token.borderBoxX += offset;

            token.y =
                    baseline - token.ascent;

            token.borderBoxY =
                    token.y;

            token.borderBoxHeight =
                    token.height;

            /*
             * Inline-block contents were laid out at 0,0 above.
             * Now that the inline-block itself has its final position,
             * lay its contents out again at the correct coordinates.
             */
            if (token.isInlineBlock()
                    && token.blockContent() != null) {

                token.blockContent().layout(
                        token.width,
                        token.x,
                        token.y
                );
            }
        }

        return lineTop + actualLineHeight;
    }

    @Override
    public void paint(Graphics2D g) {
        for (InlineBox token : tokens) {
            token.paint(g);
        }
    }

    @Override
    public float totalHeight() {
        return borderBoxHeight;
    }

    public float intrinsicWidth() {
        if (tokens.isEmpty()) {
            return 0;
        }

        float currentWidth = 0;
        float maximumWidth = 0;

        for (InlineBox token : tokens) {

            if (token.forcedBreak) {

                maximumWidth =
                        Math.max(
                                maximumWidth,
                                currentWidth
                        );

                currentWidth = 0;
                continue;
            }

            float tokenWidth = 0;

            if (token.isImage()) {

                if (token.image() != null) {
                    tokenWidth =
                            token.image()
                                    .intrinsicWidth();
                }

            } else if (token.isInlineBlock()) {

                Box child =
                        token.blockContent();

                if (child instanceof InlineFlowBox flow) {

                    tokenWidth =
                            flow.intrinsicWidth();

                } else if (child != null) {

                    tokenWidth =
                            child.getBorderBoxWidth();
                }

            } else {

                FontMetrics fm =
                        Toolkit.getDefaultToolkit()
                                .getFontMetrics(
                                        token.createFont()
                                );

                tokenWidth =
                        fm.stringWidth(token.text);

                if (token.spaceBefore) {
                    tokenWidth +=
                            fm.stringWidth(" ");
                }
            }

            currentWidth += tokenWidth;
        }

        return Math.max(
                maximumWidth,
                currentWidth
        );
    }

}