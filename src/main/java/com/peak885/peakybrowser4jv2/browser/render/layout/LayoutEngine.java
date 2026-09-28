package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import com.peak885.peakybrowser4jv2.browser.render.style.StyleResolver;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.tinylog.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class LayoutEngine {

    private final StyleResolver styleResolver;
    private final BrowserLoader browserLoader;

    public LayoutEngine(
            StyleResolver styleResolver,
            BrowserLoader browserLoader
    ) {
        this.styleResolver = styleResolver;
        this.browserLoader = browserLoader;
    }

    public Box build(
            Document document,
            float viewportWidth
    ) {
        styleResolver.setAuthorStyles(
                StyleResolver.collectAuthorCss(
                        document,
                        browserLoader
                )
        );

        Element html = document.children()
                .stream()
                .filter(e -> "html".equalsIgnoreCase(e.tagName()))
                .findFirst()
                .orElse(null);

        if (html == null) {
            html = document;
        }

        ComputedStyle htmlStyle =
                styleResolver.resolve(html, null);

        BlockBox root =
                new BlockBox(
                        htmlStyle,
                        null
                );

        root.sourceElement = html;

        InlineCollector collector =
                new InlineCollector();

        buildChildren(
                html,
                root,
                htmlStyle,
                collector,
                null
        );

        flush(
                collector,
                root,
                htmlStyle
        );

        root.layout( //<--sussy
                viewportWidth,
                0,
                0
        );

        return root;
    }

    /**
     * Re-resolves {@link ComputedStyle} for every box that has a
     * {@link Box#sourceElement}, using the current interaction state on
     * {@link StyleResolver} (:hover / :active / :focus). Geometry is left
     * alone — only paint-relevant properties change, which is enough for
     * background-color, gradients, and box-shadow on interactive states.
     */
    public void restyle(Box root) {
        if (root == null) {
            return;
        }
        restyleRecursive(root, null);
    }

    private void restyleRecursive(Box box, ComputedStyle parentStyle) {
        ComputedStyle styleForChildren = parentStyle;

        if (box.sourceElement != null) {
            ComputedStyle resolved =
                    styleResolver.resolve(box.sourceElement, parentStyle);
            box.replaceStyle(resolved);
            styleForChildren = resolved;
        } else if (box.style != null) {
            styleForChildren = box.style;
        }

        for (Box child : box.children) {
            restyleRecursive(child, styleForChildren);
        }

        // Inline-blocks / form controls live in blockContent, not children
        if (box instanceof InlineBox inline
                && inline.isInlineBlock()
                && inline.blockContent() != null) {
            restyleRecursive(inline.blockContent(), styleForChildren);
        }
    }

    private void buildChildren(
            Element parent,
            Box parentBox,
            ComputedStyle parentStyle,
            InlineCollector collector,
            String currentHref
    ) {
        for (Node node : parent.childNodes()) {

            /*
             * ---------------------------------------------------------
             * TEXT
             * ---------------------------------------------------------
             */
            if (node instanceof TextNode textNode) {

                collectText(
                        textNode.getWholeText(),
                        parentStyle,
                        currentHref,
                        collector
                );

                continue;
            }

            if (!(node instanceof Element element)) {
                continue;
            }

            String tag =
                    element.tagName()
                            .toLowerCase();

            if (isNonRenderedElement(tag)) {
                continue;
            }

            /*
             * ---------------------------------------------------------
             * BR
             * ---------------------------------------------------------
             */
            if ("br".equals(tag)) {

                collector.buffer.add(
                        InlineBox.lineBreak(
                                parentStyle
                        )
                );

                collector.pendingSpace = false;

                continue;
            }

            ComputedStyle style =
                    styleResolver.resolve(
                            element,
                            parentStyle
                    );

            if ("none".equalsIgnoreCase(
                    style.display()
            )) {
                continue;
            }

            /*
             * ---------------------------------------------------------
             * LINK
             * ---------------------------------------------------------
             */
            String href =
                    currentHref;

            if ("a".equals(tag)
                    && element.hasAttr("href")) {

                String absolute =
                        element.absUrl("href");

                href =
                        absolute.isBlank()
                                ? element.attr("href")
                                : absolute;
            }

            /*
             * ---------------------------------------------------------
             * IMAGE
             * ---------------------------------------------------------
             */
            if ("img".equals(tag)) {

                renderImage(
                        element,
                        style,
                        href,
                        collector
                );

                continue;
            }

            /*
             * ---------------------------------------------------------
             * ABSOLUTE / FIXED
             * ---------------------------------------------------------
             */
            if (style.isAbsolutelyPositioned()) {

                flush(
                        collector,
                        parentBox,
                        parentStyle
                );

                BlockBox positioned =
                        new BlockBox(
                                style,
                                null
                        );

                positioned.sourceElement =
                        element;

                parentBox.children.add(
                        positioned
                );

                InlineCollector childCollector =
                        new InlineCollector();

                buildChildren(
                        element,
                        positioned,
                        style,
                        childCollector,
                        href
                );

                flush(
                        childCollector,
                        positioned,
                        style
                );

                continue;
            }

            /*
             * ---------------------------------------------------------
             * FORM / REPLACED ELEMENT
             * ---------------------------------------------------------
             */
            if (isFormElement(tag)) {
                if ("input".equals(tag) && "hidden".equalsIgnoreCase(element.attr("type").trim())) {
                    continue;
                }

                ImageData captchaImage = loadCaptchaImage(element, style);

                ElementBox elementBox = new ElementBox(style, element, captchaImage);
                elementBox.sourceElement = element;

                // Treat form elements as inline-blocks so they flow horizontally!
                collector.buffer.add(
                        InlineBox.inlineBlock(
                                style,
                                elementBox,
                                currentHref
                        )
                );

                collector.pendingSpace = false;
                continue;
            }

            /*
             * ---------------------------------------------------------
             * INLINE BLOCK
             * ---------------------------------------------------------
             */
            if (style.isInlineBlock()) {

                BlockBox content =
                        new BlockBox(
                                style,
                                null
                        );

                content.sourceElement =
                        element;

                InlineCollector childCollector =
                        new InlineCollector();

                buildChildren(
                        element,
                        content,
                        style,
                        childCollector,
                        href
                );

                flush(
                        childCollector,
                        content,
                        style
                );

                collector.buffer.add(
                        InlineBox.inlineBlock(
                                style,
                                content,
                                href
                        )
                );

                collector.pendingSpace = false;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * BLOCK
             * ---------------------------------------------------------
             */
            if (style.isBlock()) {

                flush(
                        collector,
                        parentBox,
                        parentStyle
                );

                String marker =
                        "list-item".equalsIgnoreCase(
                                style.display()
                        )
                                ? listMarker(element)
                                : null;

                BlockBox block =
                        new BlockBox(
                                style,
                                marker
                        );

                block.sourceElement =
                        element;

                parentBox.children.add(
                        block
                );

                InlineCollector childCollector =
                        new InlineCollector();

                buildChildren(
                        element,
                        block,
                        style,
                        childCollector,
                        href
                );

                flush(
                        childCollector,
                        block,
                        style
                );

                continue;
            }

            /*
             * ---------------------------------------------------------
             * INLINE
             * ---------------------------------------------------------
             */
            buildChildren(
                    element,
                    parentBox,
                    style,
                    collector,
                    href
            );
        }
    }

    private void renderImage(
            Element element,
            ComputedStyle style,
            String href,
            InlineCollector collector
    ) {
        String src =
                element.attr("src").trim();

        if (src.isBlank()) {
            renderAltText(
                    element,
                    style,
                    href,
                    collector
            );
            return;
        }

        String imageUrl = resolveImageUrl(element, src);

        try {

            ImageData image =
                    browserLoader.loadImage(
                            imageUrl
                    );

            applyImageDimensions(element, style);

            collector.buffer.add(
                    InlineBox.image(
                            style,
                            image,
                            href
                    )
            );

            collector.pendingSpace = false;

            Logger.debug(
                    "IMG {}x{} ({})",
                    image.intrinsicWidth(),
                    image.intrinsicHeight(),
                    imageUrl
            );

        } catch (IOException e) {

            Logger.warn(
                    e,
                    "Failed to load image: {}",
                    imageUrl
            );

            renderAltText(
                    element,
                    style,
                    href,
                    collector
            );
        }
    }

    private ImageData loadCaptchaImage(
            Element element,
            ComputedStyle style
    ) {
        if (!isImageCaptchaElement(element)) {
            return null;
        }

        String imageUrl = resolveImageUrl(
                element,
                element.attr("src").trim()
        );

        try {
            ImageData image = browserLoader.loadImage(imageUrl);
            applyImageDimensions(element, style);

            Logger.debug(
                    "CAPTCHA {}x{} ({})",
                    image.intrinsicWidth(),
                    image.intrinsicHeight(),
                    imageUrl
            );

            return image;
        } catch (IOException e) {
            Logger.warn(e, "Failed to load CAPTCHA image: {}", imageUrl);
            return null;
        }
    }

    private String resolveImageUrl(Element element, String src) {
        String absolute = element.absUrl("src");
        return absolute.isBlank() ? src : absolute;
    }

    private void applyImageDimensions(
            Element element,
            ComputedStyle style
    ) {
        applyImageDimension(element, style, "width");
        applyImageDimension(element, style, "height");
    }

    private void applyImageDimension(
            Element element,
            ComputedStyle style,
            String dimension
    ) {
        if (!element.hasAttr(dimension)) {
            return;
        }

        String value = element.attr(dimension).trim();
        if (value.isEmpty()) {
            return;
        }

        if (value.matches("\\d+(\\.\\d+)?")) {
            value += "px";
        }

        style.set(dimension, value);
    }

    private void renderAltText(
            Element element,
            ComputedStyle style,
            String href,
            InlineCollector collector
    ) {
        String alt =
                element.attr("alt");

        if (!alt.isBlank()) {

            collectText(
                    "[" + alt + "]",
                    style,
                    href,
                    collector
            );
        }
    }

    private void collectText(
            String raw,
            ComputedStyle style,
            String href,
            InlineCollector collector
    ) {
        if (raw == null || raw.isEmpty()) {
            return;
        }

        /*
         * HTML collapses ordinary whitespace.
         */
        String collapsed =
                raw.replaceAll(
                        "[\\t\\n\\r ]+",
                        " "
                );

        boolean leadingSpace =
                collapsed.startsWith(" ");

        boolean trailingSpace =
                collapsed.endsWith(" ");

        String trimmed =
                collapsed.trim();

        /*
         * Whitespace-only text nodes can still
         * represent a space between inline nodes.
         */
        if (trimmed.isEmpty()) {

            if (!collector.buffer.isEmpty()) {
                collector.pendingSpace = true;
            }

            return;
        }

        String[] words =
                trimmed.split(" ");

        for (int i = 0; i < words.length; i++) {

            boolean spaceBefore;

            if (i == 0) {

                spaceBefore =
                        leadingSpace
                                || collector.pendingSpace;

            } else {

                spaceBefore = true;
            }

            collector.buffer.add(
                    new InlineBox(
                            style,
                            words[i],
                            href,
                            spaceBefore
                    )
            );
        }

        collector.pendingSpace =
                trailingSpace;
    }

    private void flush(
            InlineCollector collector,
            Box parentBox,
            ComputedStyle flowStyle
    ) {
        if (collector.buffer.isEmpty()) {
            return;
        }

        // Use the containing block's style so text-align (and other
        // inherited properties) reach closeLine(). An empty style was
        // forcing every line to left-align.
        ComputedStyle styleForFlow =
                flowStyle != null
                        ? flowStyle
                        : new ComputedStyle();

        InlineFlowBox flow =
                new InlineFlowBox(
                        styleForFlow,
                        new ArrayList<>(
                                collector.buffer
                        )
                );

        parentBox.children.add(flow);

        collector.buffer.clear();
        collector.pendingSpace = false;
    }

    private String listMarker(
            Element li
    ) {
        Element parent =
                li.parent();

        if (parent != null
                && "ol".equalsIgnoreCase(
                parent.tagName()
        )) {

            int index =
                    parent.children()
                            .select("> li")
                            .indexOf(li);

            return (index + 1) + ".";
        }

        return "•";
    }

    private boolean isNonRenderedElement(String tag) {
        return switch (tag) {
            case "head",
                 "meta",
                 "title",
                 "link",
                 "style",
                 "script",
                 "template",
                 "noscript",
                 "iframe",
                 "object",
                 "embed" -> true;

            default -> false;
        };
    }

    private boolean isFormElement(
            String tag
    ) {
        return switch (tag) {

            case "input",
                 "button",
                 "textarea",
                 "select" -> true;

            default -> false;
        };
    }

    private boolean isImageCaptchaElement(Element element) {
        if (!"input".equalsIgnoreCase(element.tagName())) {
            return false;
        }

        String src = element.attr("src").trim();
        if (src.isEmpty()) {
            return false;
        }

        String marker = String.join(
                " ",
                element.id(),
                element.className(),
                element.attr("name"),
                element.attr("title"),
                element.attr("aria-label"),
                element.attr("alt"),
                src
        ).toLowerCase();

        return marker.contains("captcha");
    }

    private static final class InlineCollector {

        final List<InlineBox> buffer =
                new ArrayList<>();

        boolean pendingSpace;
    }
}