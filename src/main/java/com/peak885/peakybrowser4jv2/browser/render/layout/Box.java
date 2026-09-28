package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;

import org.jsoup.nodes.Element;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

public abstract class Box {

    /**
     * Computed style for this box. Not final so interactive states
     * (:hover / :active / :focus) can re-resolve styles in place without
     * rebuilding the entire box tree.
     */
    public ComputedStyle style;
    public final List<Box> children = new ArrayList<>();

    /** The DOM element this box was generated from, when known (set by LayoutEngine). Used to route real clicks back into page JS via JsBridge. */
    public Element sourceElement;

    // Content box
    public float x, y, width, height;

    // Border box (for painting background later)
    public float borderBoxX, borderBoxY, borderBoxWidth, borderBoxHeight;

    protected Box(ComputedStyle style) {
        this.style = style;
    }

    public void replaceStyle(ComputedStyle newStyle) {
        if (newStyle != null) {
            this.style = newStyle;
        }
    }

    public abstract void layout(float availableWidth, float startX, float startY);

    public abstract void paint(Graphics2D g);

    public float getBorderBoxWidth() {
        return borderBoxWidth;
    }

    public float getBorderBoxHeight() {
        return borderBoxHeight;
    }

    public float totalHeight() {
        return style.marginTop()
                + borderBoxHeight
                + style.marginBottom();
    }
}
