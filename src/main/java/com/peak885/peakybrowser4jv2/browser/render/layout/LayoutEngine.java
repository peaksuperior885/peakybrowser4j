package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.js.CanvasRenderingContext2D;
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
    private Runnable pipelineInvalidator = () -> {};

    public LayoutEngine(
            StyleResolver styleResolver,
            BrowserLoader browserLoader
    ) {
        this.styleResolver = styleResolver;
        this.browserLoader = browserLoader;
    }

    public void setPipelineInvalidator(Runnable invalidator) {
        this.pipelineInvalidator = invalidator == null ? () -> {} : invalidator;
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

        // Pass browserLoader here!
        BlockBox root =
                new BlockBox(
                        htmlStyle,
                        null,
                        browserLoader
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

        root.layout(
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
            // Animated so transition: on :hover / :active actually runs.
            box.replaceStyleAnimated(resolved);
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
             * VIDEO
             * ---------------------------------------------------------
             */
            if ("video".equals(tag)) {

                renderVideo(
                        element,
                        style,
                        href,
                        collector
                );

                continue;
            }

            /*
             * ---------------------------------------------------------
             * CANVAS
             * ---------------------------------------------------------
             */
            if ("canvas".equals(tag)) {
                float w = parseDimension(element.attr("width"), 300f);
                float h = parseDimension(element.attr("height"), 150f);
                style.set("width", w + "px");
                style.set("height", h + "px");

                ElementBox canvasBox = new ElementBox(style, element, null,
                        CanvasRenderingContext2D.forElement(element, null));
                canvasBox.sourceElement = element;
                if (style.isAbsolutelyPositioned()) {
                    flush(collector, parentBox, parentStyle);
                    parentBox.children.add(canvasBox);
                } else if (style.isBlock()) {
                    flush(collector, parentBox, parentStyle);
                    parentBox.children.add(canvasBox);
                } else {
                    collector.buffer.add(InlineBox.inlineBlock(style, canvasBox, href));
                    collector.pendingSpace = false;
                }
                continue;
            }

            /*
             * ---------------------------------------------------------
             * IFRAME (Real Sub-Context Provisioning Engine)
             * ---------------------------------------------------------
             */
            if ("iframe".equals(tag)) {
                float w = parseDimension(element.attr("width"), 300f);
                float h = parseDimension(element.attr("height"), 150f);

                style.set("width", w + "px");
                style.set("height", h + "px");

                IframeBox iframeBox =
                        new IframeBox(
                                style,
                                element,
                                browserLoader,
                                this::invalidatePipeline
                        );

                if (style.isAbsolutelyPositioned() || style.isBlock()) {
                    flush(collector, parentBox, parentStyle);
                    parentBox.children.add(iframeBox);
                } else {
                    collector.buffer.add(
                            InlineBox.inlineBlock(
                                    style,
                                    iframeBox,
                                    href
                            )
                    );

                    collector.pendingSpace = false;
                }

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

                // Pass browserLoader here!
                BlockBox positioned =
                        new BlockBox(
                                style,
                                null,
                                browserLoader
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

                // Pass browserLoader here!
                BlockBox content =
                        new BlockBox(
                                style,
                                null,
                                browserLoader
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

                // Pass browserLoader here!
                BlockBox block =
                        new BlockBox(
                                style,
                                marker,
                                browserLoader
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

        applyImageDimensions(element, style);
        ImageContext image = ImageContext.forElement(element, browserLoader);
        if (image.failed()) {
            renderAltText(
                    element,
                    style,
                    href,
                    collector
            );
            return;
        }

        image.load(imageUrl, this::invalidatePipeline);
        collector.buffer.add(InlineBox.image(style, image, href));
        collector.pendingSpace = false;
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

        // Honor CSS white-space so <pre> and similar keep their formatting.
        if (style.preservesNewlines() || style.preservesSpaces()) {
            collectPreformattedText(raw, style, href, collector);
            return;
        }

        // white-space: normal / nowrap — collapse runs of whitespace to a single space
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

    /**
     * Handles white-space: pre / pre-wrap / pre-line.
     * Newlines become forced line breaks; spaces and tabs are preserved
     * (tabs expanded to 4 spaces for measurement).
     */
    private void collectPreformattedText(
            String raw,
            ComputedStyle style,
            String href,
            InlineCollector collector
    ) {
        // Normalize only \r\n / \r → \n so we have a single line terminator.
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        // Expand tabs early so FontMetrics sees ordinary spaces.
        normalized = expandTabs(normalized, 4);

        boolean preserveSpaces = style.preservesSpaces(); // pre / pre-wrap
        // pre-line collapses spaces but still keeps newlines

        int start = 0;
        int length = normalized.length();

        for (int i = 0; i <= length; i++) {
            boolean atEnd = i == length;
            boolean atNewline = !atEnd && normalized.charAt(i) == '\n';

            if (!atEnd && !atNewline) {
                continue;
            }

            // Segment from start .. i (exclusive)
            if (i > start) {
                String segment = normalized.substring(start, i);

                if (preserveSpaces) {
                    // Keep the exact characters (including multiple spaces)
                    // as a single token so consecutive spaces are not collapsed.
                    collector.buffer.add(
                            new InlineBox(style, segment, href, false)
                    );
                } else {
                    // pre-line: collapse internal whitespace runs to single spaces,
                    // but do not trim the whole segment (leading/trailing on a
                    // line can still matter for alignment in some cases; we
                    // still collapse for visual closeness to CSS).
                    String collapsed = segment.replaceAll("[ \\t]+", " ");
                    if (!collapsed.isEmpty()) {
                        // Split on spaces so soft-wrap (if allowed) can break
                        // between words; spaceBefore carries the space width.
                        String[] words = collapsed.split(" ", -1);
                        for (int w = 0; w < words.length; w++) {
                            if (words[w].isEmpty() && w == words.length - 1) {
                                // trailing space from the split
                                collector.pendingSpace = true;
                                continue;
                            }
                            boolean spaceBefore = w > 0
                                    || (w == 0 && collector.pendingSpace);
                            if (!words[w].isEmpty()) {
                                collector.buffer.add(
                                        new InlineBox(
                                                style,
                                                words[w],
                                                href,
                                                spaceBefore
                                        )
                                );
                                collector.pendingSpace = false;
                            } else if (w > 0) {
                                // consecutive spaces already collapsed
                            }
                        }
                    }
                }
            }

            if (atNewline) {
                collector.buffer.add(InlineBox.lineBreak(style));
                collector.pendingSpace = false;
            }

            start = i + 1;
        }
    }

    private static String expandTabs(String text, int tabSize) {
        if (text.indexOf('\t') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        int col = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                int spaces = tabSize - (col % tabSize);
                for (int s = 0; s < spaces; s++) {
                    sb.append(' ');
                }
                col += spaces;
            } else {
                sb.append(c);
                if (c == '\n') {
                    col = 0;
                } else {
                    col++;
                }
            }
        }
        return sb.toString();
    }

    private void flush(
            InlineCollector collector,
            Box parentBox,
            ComputedStyle flowStyle
    ) {
        if (collector.buffer.isEmpty()) {
            return;
        }

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
            case "head", "meta", "title", "link", "style",
                 "script", "template", "noscript",
                 "object", "embed" -> true;
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

    private void renderVideo(
            Element element,
            ComputedStyle style,
            String href,
            InlineCollector collector
    ) {
        VideoBox videoBox = new VideoBox(
                style,
                element,
                browserLoader
        );

        collector.buffer.add(
                InlineBox.inlineBlock(
                        style,
                        videoBox,
                        href
                )
        );

        collector.pendingSpace = false;
    }

    private float parseDimension(String value, float defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        value = value.trim();

        try {
            if (value.matches("\\d+(\\.\\d+)?")) {
                return Float.parseFloat(value);
            }

            if (value.toLowerCase().endsWith("px")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2).trim()
                );
            }

            if (value.endsWith("%")) {
                return defaultValue;
            }

        } catch (NumberFormatException ignored) {
        }

        return defaultValue;
    }

    private void invalidatePipeline() {
        javax.swing.SwingUtilities.invokeLater(pipelineInvalidator);
    }
}