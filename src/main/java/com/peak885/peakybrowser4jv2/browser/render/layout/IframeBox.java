package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.jsoup.nodes.Element;
import java.awt.*;
import java.util.Map;
import java.util.WeakHashMap;

public final class IframeBox extends Box {

    // Cache mappings directly matching JSoup node instances to ensure contexts aren't lost across ticks
    private static final Map<Element, IframeContext> CONTEXT_REGISTRY = new WeakHashMap<>();

    private final Element element;
    private final BrowserLoader loader;
    private final IframeContext frameContext;

    public IframeBox(ComputedStyle style, Element element, BrowserLoader loader, Runnable uiInvalidator) {
        super(style);
        this.element = element;
        this.loader = loader;
        this.sourceElement = element;

        // Ensure a single persistent iframe frame shell lifecycle exists for this element
        this.frameContext = CONTEXT_REGISTRY.computeIfAbsent(element,
                el -> new IframeContext(el, loader, uiInvalidator));
    }

    @Override
    public void layout(float availableWidth, float startX, float startY) {
        borderBoxX = startX + style.marginLeft();
        borderBoxY = startY + style.marginTop();

        // 1. Resolve bounding boundaries defined by node CSS attributes or stylesheets
        width = style.width(300f, style.fontSize(), availableWidth);
        height = style.height(150f, style.fontSize(), availableWidth);

        borderBoxWidth = width + style.paddingLeft() + style.paddingRight();
        borderBoxHeight = height + style.paddingTop() + style.paddingBottom();

        x = borderBoxX + style.paddingLeft();
        y = borderBoxY + style.paddingTop();

        // 2. Instruct sub-context layout workers to process internal elements matching frame coordinates
        frameContext.loadAsync(width);
        frameContext.computeSubLayout(width, x, y);
    }

    @Override
    public void paint(Graphics2D g) {
        int clipX = Math.round(x);
        int clipY = Math.round(y);
        int clipWidth = Math.max(0, Math.round(width));
        int clipHeight = Math.max(0, Math.round(height));

        g.setColor(new Color(210, 210, 210));

        g.drawRect(
                Math.round(borderBoxX),
                Math.round(borderBoxY),
                Math.max(0, Math.round(borderBoxWidth)),
                Math.max(0, Math.round(borderBoxHeight))
        );

        Shape oldClip = g.getClip();

        try {
            g.clipRect(
                    clipX,
                    clipY,
                    clipWidth,
                    clipHeight
            );

            frameContext.paintSubTree(g);
        } finally {
            g.setClip(oldClip);
        }
    }
}
