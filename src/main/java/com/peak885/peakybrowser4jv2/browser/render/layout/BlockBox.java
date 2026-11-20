package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.tinylog.Logger;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class BlockBox extends Box {

    private final String marker;
    private final BrowserLoader browserLoader;

    public BlockBox(
            ComputedStyle style,
            String marker
    ) {
        this(style, marker, null);
    }

    public BlockBox(
            ComputedStyle style,
            String marker,
            BrowserLoader browserLoader
    ) {
        super(style);
        this.marker = marker;
        this.browserLoader = browserLoader;
    }

    @Override
    public void layout(
            float availableWidth,
            float startX,
            float startY
    ) {
        float marginTop = style.marginTop();
        float marginRight = style.marginRight();
        float marginBottom = style.marginBottom();
        float marginLeft = style.marginLeft();

        float paddingTop = style.paddingTop();
        float paddingRight = style.paddingRight();
        float paddingBottom = style.paddingBottom();
        float paddingLeft = style.paddingLeft();

        borderBoxX = startX + marginLeft;
        borderBoxY = startY + marginTop;
        borderBoxWidth = Math.max(0, availableWidth - marginLeft - marginRight);

        boolean isBorderBox = style.isBorderBox();

        if (isBorderBox) {
            float explicitWidth = style.width(-1f, style.fontSize(), borderBoxWidth);
            if (explicitWidth >= 0f) {
                borderBoxWidth = explicitWidth;
            }

            width = Math.max(0, borderBoxWidth - paddingLeft - paddingRight);
            x = borderBoxX + paddingLeft;
        } else {
            x = borderBoxX + paddingLeft;
            width = Math.max(0, borderBoxWidth - paddingLeft - paddingRight);
        }

        /*
         * Content box.
         */
        x = borderBoxX + paddingLeft;
        y = borderBoxY + paddingTop;
        width = Math.max(0, borderBoxWidth - paddingLeft - paddingRight);

        List<Box> normalChildren = new ArrayList<>();
        List<Box> positionedChildren = new ArrayList<>();

        for (Box child : children) {
            if (child.style.isAbsolutelyPositioned()) {
                positionedChildren.add(child);
            } else {
                normalChildren.add(child);
            }
        }

        float contentHeight;

        /*
         * -------------------------------------------------------------
         * FORCED FLEX ROW (TESTING OVERRIDE)
         * -------------------------------------------------------------
         */
        boolean forceFlexTest = false;

        if ((style.isFlex() || forceFlexTest) && !style.isFlexColumn()) {
            contentHeight = layoutFlexRow(normalChildren);
        } else {
            contentHeight = layoutVertical(normalChildren);
        }

        float resolvedHeight = style.height(-1f, style.fontSize(), width);
        float resolvedMinHeight = style.minHeight(-1f, style.fontSize(), width);
        float resolvedMaxHeight = style.maxHeight(Float.MAX_VALUE, style.fontSize(), width);
        boolean borderBox = style.isBorderBox();
        float verticalPadding = paddingTop + paddingBottom;

        if (resolvedHeight >= 0f) {
            contentHeight = borderBox
                    ? Math.max(0f, resolvedHeight - verticalPadding)
                    : resolvedHeight;
        }
        if (resolvedMinHeight >= 0f) {
            float minContentHeight = borderBox
                    ? Math.max(0f, resolvedMinHeight - verticalPadding)
                    : resolvedMinHeight;
            contentHeight = Math.max(contentHeight, minContentHeight);
        }
        if (resolvedMaxHeight < Float.MAX_VALUE) {
            float maxContentHeight = borderBox
                    ? Math.max(0f, resolvedMaxHeight - verticalPadding)
                    : resolvedMaxHeight;
            contentHeight = Math.min(contentHeight, maxContentHeight);
        }

        height = Math.max(0, contentHeight);
        borderBoxHeight = height + paddingTop + paddingBottom;

        for (Box child : positionedChildren) {
            layoutAbsoluteChild(child);
        }
    }

    private float layoutVertical(List<Box> children) {
        float cursorY = y;

        for (Box child : children) {
            float childWidth = width;
            child.layout(childWidth, x, cursorY);
            cursorY += child.totalHeight();
        }

        return Math.max(0, cursorY - y);
    }

    private float layoutFlexRow(List<Box> children) {
        Logger.debug("layoutFlexRow called! Children count: {}, justifyContent: {}", children.size(), style.get("justify-content"));
        if (children.isEmpty()) {
            return 0;
        }

        float gap = Math.max(0, style.gap());
        boolean wrap = style.isFlexWrap();
        String justifyContent = style.get("justify-content");

        List<List<FlexItem>> rows = new ArrayList<>();
        List<FlexItem> currentRow = new ArrayList<>();
        float currentRowWidth = 0;

        for (Box child : children) {
            float childWidth = resolveFlexChildWidth(child);
            childWidth = Math.max(0, childWidth);

            boolean shouldWrap = wrap
                    && !currentRow.isEmpty()
                    && (currentRowWidth + gap + childWidth > width);

            if (shouldWrap) {
                rows.add(new ArrayList<>(currentRow));
                currentRow.clear();
                currentRowWidth = 0;
            }

            currentRow.add(new FlexItem(child, childWidth));
            currentRowWidth += (currentRow.size() == 1 ? childWidth : gap + childWidth);
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        float rowY = y;
        float totalHeight = 0;

        for (List<FlexItem> row : rows) {
            float rowWidth = 0;
            for (int i = 0; i < row.size(); i++) {
                rowWidth += (i == 0 ? row.get(i).width : gap + row.get(i).width);
            }

            float freeSpace = Math.max(0, width - rowWidth);
            float cursorX = x;
            float itemSpacing = gap;

            if (justifyContent != null) {
                String jc = justifyContent.trim().toLowerCase();
                if (jc.equals("center")) {
                    cursorX = x + (freeSpace / 2f);
                } else if (jc.equals("flex-end")) {
                    cursorX = x + freeSpace;
                } else if (jc.equals("space-between") && row.size() > 1) {
                    itemSpacing = gap + (freeSpace / (row.size() - 1));
                } else if (jc.equals("space-around") && !row.isEmpty()) {
                    float spacePerItem = freeSpace / row.size();
                    cursorX = x + (spacePerItem / 2f);
                    itemSpacing = gap + spacePerItem;
                }
            }

            float rowHeight = 0;

            for (int i = 0; i < row.size(); i++) {
                FlexItem item = row.get(i);

                item.child.layout(item.width, cursorX, rowY);

                item.child.x = cursorX;
                item.child.borderBoxX = cursorX;
                item.child.y = rowY;
                item.child.borderBoxY = rowY;

                float actualWidth = item.child.getBorderBoxWidth();
                if (actualWidth <= 0) {
                    actualWidth = item.width;
                }

                rowHeight = Math.max(rowHeight, item.child.totalHeight());
                cursorX += actualWidth + (i == 0 ? 0 : itemSpacing - gap) + gap;
            }

            rowY += rowHeight + gap;
            totalHeight = rowY - y - gap;
        }

        return Math.max(0, totalHeight);
    }

    private static final class FlexItem {
        final Box child;
        final float width;

        FlexItem(Box child, float width) {
            this.child = child;
            this.width = width;
        }
    }

    private float resolveFlexChildWidth(Box child) {
        float available = Math.max(0f, width);
        float explicitWidth = child.style.width(-1f, child.style.fontSize(), available);

        if (explicitWidth >= 0f) {
            return explicitWidth;
        }

        if (child instanceof InlineFlowBox inlineFlow) {
            float intrinsic = inlineFlow.intrinsicWidth();
            if (intrinsic > 0f) return intrinsic;
        }

        if (!child.children.isEmpty()) {
            float natural = naturalContentWidth(child, available);
            if (natural > 0f) return natural;
        }

        float existing = child.getBorderBoxWidth();
        if (existing > 0f) return existing;

        if (child instanceof BlockBox) return available;
        return 0f;
    }

    private void layoutAbsoluteChild(Box child) {
        float childWidth = child.style.width(-1f, child.style.fontSize(), width);

        if (childWidth <= 0) {
            child.layout(width, 0, 0);
            childWidth = naturalContentWidth(child, width);
        }

        childWidth = Math.max(0, childWidth);
        child.layout(childWidth, 0, 0);

        Float left = child.style.leftOffset(width);
        Float right = child.style.rightOffset(width);
        Float top = child.style.topOffset(height);
        Float bottom = child.style.bottomOffset(height);

        float marginLeft = style.marginLeft();
        float marginTop = style.marginTop();
        float childWidthActual = child.getBorderBoxWidth();
        float childHeightActual = child.getBorderBoxHeight();

        float finalX;
        if (left != null) {
            finalX = x + left;
        } else if (right != null) {
            finalX = x + width - right - childWidthActual;
        } else {
            finalX = x;
        }

        float finalY;
        if (top != null) {
            finalY = y + top;
        } else if (bottom != null) {
            finalY = y + height - bottom - childHeightActual;
        } else {
            finalY = y;
        }

        child.layout(childWidth, finalX - marginLeft, finalY - marginTop);
    }

    private static float naturalContentWidth(Box box, float availableWidth) {
        float measured = 0;

        for (Box child : box.children) {
            if (child instanceof InlineFlowBox flow) {
                measured = Math.max(measured, flow.intrinsicWidth());
            } else if (!child.children.isEmpty()) {
                measured = Math.max(measured, naturalContentWidth(child, availableWidth));
            } else {
                measured = Math.max(measured, child.getBorderBoxWidth());
            }
        }

        if (measured <= 0) {
            return availableWidth;
        }

        return Math.min(availableWidth, measured);
    }

    @Override
    public void paint(Graphics2D g) {
        int bx = Math.round(borderBoxX);
        int by = Math.round(borderBoxY);
        int bw = Math.round(borderBoxWidth);
        int bh = Math.round(borderBoxHeight);
        float radius = style.borderRadius();
        int diameter = radius > 0 ? Math.round(radius * 2) : 0;

        long now = System.currentTimeMillis();
        ComputedStyle.Transform tx = style.transform();
        Color animBg = null;
        ComputedStyle.BoxShadow animShadow = null;
        if (anim != null) {
            if (anim.done(now)) {
                anim = null;
            } else {
                tx = anim.transform(now);
                animBg = anim.background(now);
                animShadow = anim.shadow(now);
            }
        }

        java.awt.geom.AffineTransform saved = null;
        if (!tx.isIdentity()) {
            saved = g.getTransform();
            // Transform origin: center of the border box (matches common UI intent)
            float cx = borderBoxX + borderBoxWidth / 2f;
            float cy = borderBoxY + borderBoxHeight / 2f;
            g.translate(cx + tx.translateX(), cy + tx.translateY());
            g.rotate(Math.toRadians(tx.rotateDeg()));
            g.scale(tx.scaleX(), tx.scaleY());
            g.translate(-cx, -cy);
        }

        paintBoxShadow(g, bx, by, bw, bh, diameter, animShadow);

        String bgUrl = style.backgroundImageUrl();
        if (bgUrl != null && !bgUrl.isBlank() && sourceElement != null && browserLoader != null) {
            String absoluteUrl = bgUrl;
            try {
                absoluteUrl = java.net.URI.create(sourceElement.baseUri()).resolve(bgUrl).toString();
            } catch (Exception ignored) {}

            ImageContext imgCtx = ImageContext.forElement(sourceElement, browserLoader);
            imgCtx.load(absoluteUrl, () -> {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    for (java.awt.Window w : java.awt.Window.getWindows()) {
                        if (w.isDisplayable()) w.repaint();
                    }
                });
            });

            ImageData imgData = imgCtx.image();
            if (imgData != null && imgData.firstFrame() != null) {
                BufferedImage frame = imgData.firstFrame();
                paintBackgroundImage(g, frame, bx, by, bw, bh, style);
            } else {
                paintSolidOrGradientBackground(g, bx, by, bw, bh, radius, diameter, animBg);
            }
        } else {
            paintSolidOrGradientBackground(g, bx, by, bw, bh, radius, diameter, animBg);
        }

        if (marker != null) {
            Font font = new Font("SansSerif", Font.PLAIN, Math.max(1, Math.round(style.fontSize())));
            g.setFont(font);
            g.setColor(style.color());

            FontMetrics fm = g.getFontMetrics();
            float markerX = Math.max(0, x - fm.stringWidth(marker) - 6);
            float markerY = y + fm.getAscent();

            g.drawString(marker, Math.round(markerX), Math.round(markerY));
        }

        for (Box child : children) {
            child.paint(g);
        }

        if (saved != null) {
            g.setTransform(saved);
        }
    }

    /**
     * Paint background images with basic CSS sizing, positioning, and repeat.
     * Positioning matters for sprite sheets: scaling the entire sheet into each
     * element hides the individual icon or challenge tile selected by CSS.
     */
    private static void paintBackgroundImage(
            Graphics2D g,
            BufferedImage image,
            int bx,
            int by,
            int bw,
            int bh,
            ComputedStyle style
    ) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0 || bw <= 0 || bh <= 0) {
            return;
        }

        double drawWidth = image.getWidth();
        double drawHeight = image.getHeight();
        String size = effectiveBackgroundValue(style, "background-size").trim().toLowerCase();
        String[] sizeParts = size.isBlank() ? new String[0] : size.split("\\s+");

        if ("cover".equals(size)) {
            double scale = Math.max((double) bw / drawWidth, (double) bh / drawHeight);
            drawWidth *= scale;
            drawHeight *= scale;
        } else if ("contain".equals(size)) {
            double scale = Math.min((double) bw / drawWidth, (double) bh / drawHeight);
            drawWidth *= scale;
            drawHeight *= scale;
        } else if (sizeParts.length > 0) {
            Double requestedWidth = backgroundSizeLength(sizeParts[0], bw);
            Double requestedHeight = sizeParts.length > 1
                    ? backgroundSizeLength(sizeParts[1], bh)
                    : null;
            if (requestedWidth != null && requestedHeight != null) {
                drawWidth = requestedWidth;
                drawHeight = requestedHeight;
            } else if (requestedWidth != null) {
                drawHeight *= requestedWidth / drawWidth;
                drawWidth = requestedWidth;
            } else if (requestedHeight != null) {
                drawWidth *= requestedHeight / drawHeight;
                drawHeight = requestedHeight;
            }
        }

        if (drawWidth <= 0 || drawHeight <= 0 || !Double.isFinite(drawWidth) || !Double.isFinite(drawHeight)) {
            return;
        }

        String[] position = backgroundPositionParts(
                effectiveBackgroundValue(style, "background-position", "0% 0%")
        );
        double offsetX = backgroundPositionOffset(position[0], bw - drawWidth);
        double offsetY = backgroundPositionOffset(position[1], bh - drawHeight);
        int drawWidthPx = Math.max(1, (int) Math.round(drawWidth));
        int drawHeightPx = Math.max(1, (int) Math.round(drawHeight));
        int startX = bx + (int) Math.round(offsetX);
        int startY = by + (int) Math.round(offsetY);

        String repeat = effectiveBackgroundValue(style, "background-repeat", "repeat").trim().toLowerCase();
        String[] repeatParts = repeat.split("\\s+");
        boolean repeatX = repeat.equals("repeat") || repeat.equals("repeat-x")
                || (repeatParts.length > 0 && "repeat".equals(repeatParts[0]));
        boolean repeatY = repeat.equals("repeat") || repeat.equals("repeat-y")
                || (repeatParts.length > 1 && "repeat".equals(repeatParts[1]));
        if (repeat.equals("no-repeat")) {
            repeatX = false;
            repeatY = false;
        }

        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clipRect(bx, by, bw, bh);
            int firstX = startX;
            int firstY = startY;
            if (repeatX) {
                while (firstX > bx) firstX -= drawWidthPx;
            }
            if (repeatY) {
                while (firstY > by) firstY -= drawHeightPx;
            }

            int maxX = repeatX ? bx + bw : startX + 1;
            int maxY = repeatY ? by + bh : startY + 1;
            int countX = 0;
            for (int py = firstY; py < maxY && countX < 1024; py += repeatY ? drawHeightPx : drawHeightPx + bh + 1) {
                int countY = 0;
                for (int px = firstX; px < maxX && countY < 1024; px += repeatX ? drawWidthPx : drawWidthPx + bw + 1) {
                    clipped.drawImage(image, px, py, drawWidthPx, drawHeightPx, null);
                    countY++;
                }
                countX++;
            }
        } finally {
            clipped.dispose();
        }
    }

    private static Double backgroundSizeLength(String value, double basis) {
        value = value.trim().toLowerCase();
        if (value.equals("auto") || value.isEmpty()) return null;
        try {
            if (value.endsWith("%")) {
                return Double.parseDouble(value.substring(0, value.length() - 1)) * basis / 100d;
            }
            if (value.endsWith("px")) {
                return Double.parseDouble(value.substring(0, value.length() - 2));
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String effectiveBackgroundValue(ComputedStyle style, String property) {
        return effectiveBackgroundValue(style, property, "");
    }

    private static String effectiveBackgroundValue(ComputedStyle style, String property, String fallback) {
        String longhand = style.get(property).trim();
        if (!longhand.isEmpty()) return longhand;

        String shorthand = style.get("background").trim();
        if (shorthand.isEmpty()) return fallback;
        String tokensOnly = shorthand.replaceAll("(?i)url\\([^)]*\\)", " ").replace("/", " / ");
        String[] tokens = tokensOnly.trim().split("\\s+");
        int slash = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals("/")) {
                slash = i;
                break;
            }
        }

        List<String> values = new ArrayList<>();
        if (property.equals("background-size")) {
            if (slash < 0) return fallback;
            for (int i = slash + 1; i < tokens.length && values.size() < 2; i++) {
                String token = tokens[i].toLowerCase();
                if (isBackgroundRepeatToken(token)) break;
                if (isBackgroundSizeToken(token)) values.add(token);
            }
        } else if (property.equals("background-repeat")) {
            for (String raw : tokens) {
                String token = raw.toLowerCase();
                if (isBackgroundRepeatToken(token)) values.add(token);
            }
        } else if (property.equals("background-position")) {
            int limit = slash < 0 ? tokens.length : slash;
            for (int i = 0; i < limit && values.size() < 2; i++) {
                String token = tokens[i].toLowerCase();
                if (isBackgroundPositionToken(token)) values.add(token);
            }
        }
        return values.isEmpty() ? fallback : String.join(" ", values);
    }

    private static boolean isBackgroundRepeatToken(String token) {
        return token.equals("repeat") || token.equals("no-repeat") || token.equals("repeat-x")
                || token.equals("repeat-y") || token.equals("space") || token.equals("round");
    }

    private static boolean isBackgroundSizeToken(String token) {
        return token.equals("auto") || token.equals("cover") || token.equals("contain")
                || token.endsWith("px") || token.endsWith("%") || token.matches("[-+]?(?:\\d*\\.)?\\d+");
    }

    private static boolean isBackgroundPositionToken(String token) {
        return token.equals("left") || token.equals("right") || token.equals("top")
                || token.equals("bottom") || token.equals("center") || token.equals("auto")
                || token.endsWith("px") || token.endsWith("%")
                || token.matches("[-+]?(?:\\d*\\.)?\\d+");
    }

    private static String[] backgroundPositionParts(String value) {
        String[] parts = value == null ? new String[0] : value.trim().toLowerCase().split("\\s+");
        if (parts.length == 0 || (parts.length == 1 && parts[0].isBlank())) return new String[]{"0%", "0%"};
        if (parts.length == 1) {
            String one = parts[0];
            if (one.equals("center")) return new String[]{"center", "center"};
            if (one.equals("top") || one.equals("bottom")) return new String[]{"center", one};
            return new String[]{one, "center"};
        }
        if ((parts[0].equals("top") || parts[0].equals("bottom"))
                && (parts[1].equals("left") || parts[1].equals("right") || parts[1].equals("center"))) {
            return new String[]{parts[1], parts[0]};
        }
        return new String[]{parts[0], parts[1]};
    }

    private static double backgroundPositionOffset(String value, double remainingSpace) {
        return switch (value) {
            case "left", "top" -> 0d;
            case "center" -> remainingSpace / 2d;
            case "right", "bottom" -> remainingSpace;
            default -> {
                Double amount = backgroundSizeLength(value, remainingSpace);
                yield amount == null ? 0d : amount;
            }
        };
    }

    private void paintSolidOrGradientBackground(
            Graphics2D g,
            int bx,
            int by,
            int bw,
            int bh,
            float radius,
            int diameter,
            Color animOverride
    ) {
        if (animOverride != null) {
            if (animOverride.getAlpha() > 0) {
                g.setPaint(animOverride);
                if (radius > 0) {
                    g.fillRoundRect(bx, by, bw, bh, diameter, diameter);
                } else {
                    g.fillRect(bx, by, bw, bh);
                }
            }
            return;
        }

        // Solid base first so multi-layer backgrounds that end in a solid
        // color (maven site: radials + var(--bg-deep)) don't leave a white page.
        Color base = style.backgroundColor();
        if (base != null && base.getAlpha() > 0) {
            g.setColor(base);
            if (radius > 0) {
                g.fillRoundRect(bx, by, bw, bh, diameter, diameter);
            } else {
                g.fillRect(bx, by, bw, bh);
            }
        }

        Paint background = style.backgroundPaint(borderBoxWidth, borderBoxHeight);
        if (background != null) {
            if (background instanceof Color color) {
                if (color.getAlpha() == 0) {
                    return;
                }
                if (base != null && color.getRGB() == base.getRGB()) {
                    return;
                }
            }
            g.setPaint(background);
            if (radius > 0) {
                g.fillRoundRect(bx, by, bw, bh, diameter, diameter);
            } else {
                g.fillRect(bx, by, bw, bh);
            }
        }
    }

    private void paintBoxShadow(
            Graphics2D g,
            int bx,
            int by,
            int bw,
            int bh,
            int diameter,
            ComputedStyle.BoxShadow animOverride
    ) {
        ComputedStyle.BoxShadow shadow =
                animOverride != null ? animOverride : style.boxShadow();
        if (shadow == null || bw <= 0 || bh <= 0) {
            return;
        }

        int steps = Math.max(1, Math.min(12, Math.round(shadow.blur())));
        for (int i = steps; i >= 1; i--) {
            float t = (float) i / steps;
            int alpha = Math.max(
                    1,
                    Math.round(shadow.color().getAlpha() * (1f - t * 0.65f) / steps)
            );
            Color c = new Color(
                    shadow.color().getRed(),
                    shadow.color().getGreen(),
                    shadow.color().getBlue(),
                    alpha
            );
            g.setColor(c);
            int expand = Math.round(shadow.blur() * t);
            int sx = bx + Math.round(shadow.offsetX()) - expand;
            int sy = by + Math.round(shadow.offsetY()) - expand;
            int sw = bw + expand * 2;
            int sh = bh + expand * 2;
            if (diameter > 0) {
                g.fillRoundRect(sx, sy, sw, sh, diameter + expand, diameter + expand);
            } else {
                g.fillRect(sx, sy, sw, sh);
            }
        }
    }
}
