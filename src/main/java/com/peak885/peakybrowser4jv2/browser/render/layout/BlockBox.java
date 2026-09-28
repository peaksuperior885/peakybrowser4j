package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.tinylog.Logger;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public final class BlockBox extends Box {

    private final String marker;

    public BlockBox(
            ComputedStyle style,
            String marker
    ) {
        super(style);
        this.marker = marker;
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
        x =
                borderBoxX + paddingLeft;

        y =
                borderBoxY + paddingTop;

        width =
                Math.max(
                        0,
                        borderBoxWidth
                                - paddingLeft
                                - paddingRight
                );

        List<Box> normalChildren =
                new ArrayList<>();

        List<Box> positionedChildren =
                new ArrayList<>();

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
        boolean forceFlexTest = false; // Flip to false later

        if ((style.isFlex() || forceFlexTest)
                && !style.isFlexColumn()) {

            contentHeight =
                    layoutFlexRow(
                            normalChildren
                    );

        }

        /*
         * -------------------------------------------------------------
         * NORMAL BLOCK / FLEX COLUMN
         * -------------------------------------------------------------
         */
        else {

            contentHeight =
                    layoutVertical(
                            normalChildren
                    );
        }

        height =
                Math.max(
                        0,
                        contentHeight
                );

        borderBoxHeight =
                height
                        + paddingTop
                        + paddingBottom;

        /*
         * Absolutely positioned children do not affect height.
         */
        for (Box child : positionedChildren) {
            layoutAbsoluteChild(child);
        }
    }

    private float layoutVertical(List<Box> children) {
        float cursorY = y;

        for (Box child : children) {
            float childWidth = width;

            child.layout(
                    childWidth,
                    x,
                    cursorY
            );

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
        String justifyContent = style.get("justify-content"); // e.g., center, flex-end, space-between

        List<List<FlexItem>> rows = new ArrayList<>();
        List<FlexItem> currentRow = new ArrayList<>();
        float currentRowWidth = 0;

        // --- PASS 1: Group children into rows and measure widths ---
        for (Box child : children) {
            float childWidth = resolveFlexChildWidth(child);
            childWidth = Math.max(0, childWidth);

            // Check if we need to wrap to the next row
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

        // --- PASS 2: Position items and apply justify-content alignment ---
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

            // Handle alignment properties
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
                cursorX += actualWidth + (i == 0 ? 0 : itemSpacing - gap) + gap; // adjust spacing
            }

            rowY += rowHeight + gap;
            totalHeight = rowY - y - gap; // remove trailing gap
        }

        return Math.max(0, totalHeight);
    }

    // Helper structure for tracking row items during layout passes
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

        // Explicit CSS width.
        float explicitWidth = child.style.width(
                -1f,
                child.style.fontSize(),
                available
        );

        if (explicitWidth >= 0f) {
            return explicitWidth;
        }

        // Inline flow intrinsic width.
        if (child instanceof InlineFlowBox inlineFlow) {
            float intrinsic = inlineFlow.intrinsicWidth();

            if (intrinsic > 0f) {
                return intrinsic;
            }
        }

        // Content-based width.
        if (!child.children.isEmpty()) {
            float natural = naturalContentWidth(child, available);

            if (natural > 0f) {
                return natural;
            }
        }

        // Already-established layout width.
        float existing = child.getBorderBoxWidth();

        if (existing > 0f) {
            return existing;
        }

        // Unresolved block-level flex items should not collapse to zero.
        if (child instanceof BlockBox) {
            return available;
        }

        return 0f;
    }

    private void layoutAbsoluteChild(
            Box child
    ) {
        float childWidth =
                child.style.width(
                        -1f,
                        child.style.fontSize(),
                        width
                );

        /*
         * Auto width: measure first.
         */
        if (childWidth <= 0) {

            child.layout(
                    width,
                    0,
                    0
            );

            childWidth =
                    naturalContentWidth(
                            child,
                            width
                    );
        }

        childWidth =
                Math.max(
                        0,
                        childWidth
                );

        /*
         * Initial measurement.
         */
        child.layout(
                childWidth,
                0,
                0
        );

        Float left =
                child.style.leftOffset(width);

        Float right =
                child.style.rightOffset(width);

        Float top =
                child.style.topOffset(height);

        Float bottom =
                child.style.bottomOffset(height);

        float marginLeft =
                child.style.marginLeft();

        float marginTop =
                child.style.marginTop();

        float childWidthActual =
                child.getBorderBoxWidth();

        float childHeightActual =
                child.getBorderBoxHeight();

        float finalX;

        if (left != null) {

            finalX =
                    x + left;

        } else if (right != null) {

            finalX =
                    x
                            + width
                            - right
                            - childWidthActual;

        } else {

            finalX = x;
        }

        float finalY;

        if (top != null) {

            finalY =
                    y + top;

        } else if (bottom != null) {

            finalY =
                    y
                            + height
                            - bottom
                            - childHeightActual;

        } else {

            finalY = y;
        }

        child.layout(
                childWidth,
                finalX - marginLeft,
                finalY - marginTop
        );
    }

    private static float naturalContentWidth(
            Box box,
            float availableWidth
    ) {
        float measured = 0;

        for (Box child : box.children) {
            if (child instanceof InlineFlowBox flow) {
                measured = Math.max(
                        measured,
                        flow.intrinsicWidth()
                );
            } else if (!child.children.isEmpty()) {
                // Recursively check nested block containers
                measured = Math.max(
                        measured,
                        naturalContentWidth(child, availableWidth)
                );
            } else {
                measured = Math.max(
                        measured,
                        child.getBorderBoxWidth()
                );
            }
        }

        if (measured <= 0) {
            return availableWidth; // If truly empty, default to available space instead of 0
        }

        return Math.min(
                availableWidth,
                measured
        );
    }

    @Override
    public void paint(Graphics2D g) {

        int bx = Math.round(borderBoxX);
        int by = Math.round(borderBoxY);
        int bw = Math.round(borderBoxWidth);
        int bh = Math.round(borderBoxHeight);
        float radius = style.borderRadius();
        int diameter = radius > 0 ? Math.round(radius * 2) : 0;

        paintBoxShadow(g, bx, by, bw, bh, diameter);

        Paint background =
                style.backgroundPaint(
                        borderBoxWidth,
                        borderBoxHeight
                );

        if (background != null) {

            boolean visible = true;

            if (background instanceof Color color) {
                visible = color.getAlpha() > 0;
            }

            if (visible) {

                g.setPaint(background);

                if (radius > 0) {
                    g.fillRoundRect(bx, by, bw, bh, diameter, diameter);
                } else {
                    g.fillRect(bx, by, bw, bh);
                }
            }
        }

        /*
         * List marker.
         */
        if (marker != null) {

            Font font =
                    new Font(
                            "SansSerif",
                            Font.PLAIN,
                            Math.max(
                                    1,
                                    Math.round(
                                            style.fontSize()
                                    )
                            )
                    );

            g.setFont(font);
            g.setColor(style.color());

            FontMetrics fm =
                    g.getFontMetrics();

            float markerX =
                    Math.max(
                            0,
                            x
                                    - fm.stringWidth(marker)
                                    - 6
                    );

            float markerY =
                    y + fm.getAscent();

            g.drawString(
                    marker,
                    Math.round(markerX),
                    Math.round(markerY)
            );
        }

        /*
         * Children.
         */
        for (Box child : children) {
            child.paint(g);
        }
    }

    private void paintBoxShadow(
            Graphics2D g,
            int bx,
            int by,
            int bw,
            int bh,
            int diameter
    ) {
        ComputedStyle.BoxShadow shadow = style.boxShadow();
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
