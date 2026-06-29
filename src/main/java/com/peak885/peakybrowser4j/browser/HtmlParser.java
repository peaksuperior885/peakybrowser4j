package com.peak885.peakybrowser4j.browser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.mozilla.javascript.*;
import org.tinylog.Logger;

public class HtmlParser {

    private StringBuilder collectedCss = new StringBuilder();
    private final PageLoader pageLoader;
    private StringBuilder collectedScripts = new StringBuilder();

    public HtmlParser(PageLoader pageLoader) {
        this.pageLoader = pageLoader;
    }

    public Node parse(String html) {
        Logger.info("Parsing HTML ({} chars)", html.length());
        long start = System.currentTimeMillis();

        collectedCss = new StringBuilder();
        collectedScripts = new StringBuilder();
        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        Node root = new Node();
        root.type = Node.Type.ELEMENT;
        root.tag = "body";

        int[] counters = new int[2];

        // Build from the root document to catch <head> elements like <link>
        build(doc, root, 0, counters);

        long time = System.currentTimeMillis() - start;
        Logger.info("Parsed DOM → elements={}, textNodes={}, css={} chars, time={}ms",
                counters[0], counters[1], collectedCss.length(), time);

        return root;
    }

    public String getCollectedScripts() {
        return collectedScripts.toString();
    }

    public String getCollectedCss() {
        return collectedCss.toString();
    }

    private void build(org.jsoup.nodes.Node el, Node parent, int depth, int[] counters) {
        for (org.jsoup.nodes.Node child : el.childNodes()) {
            if (child instanceof Element element) {
                String tag = element.tagName().toLowerCase();

                if (tag.equals("video")) {
                    Node node = new Node();
                    node.tag = "video";
                    node.type = Node.Type.ELEMENT;
                    node.isVideoNode = true;  // ← ADD THIS

                    String videoUrl = null;

                    // 1. Prefer <source src="...">
                    Element source = element.selectFirst("source[src]");
                    if (source != null) {
                        videoUrl = source.attr("src");
                    }

                    // 2. Fallback to <video src="...">
                    if (videoUrl == null || videoUrl.isBlank()) {
                        videoUrl = element.attr("src");
                    }

                    // 3. Resolve using YOUR system (not Jsoup)
                    if (videoUrl != null && !videoUrl.isBlank()) {
                        videoUrl = pageLoader.resolveUrl(videoUrl);
                    }

                    node.videoUrl = videoUrl;

                    // EXTRACT WIDTH/HEIGHT ← ADD THIS BLOCK
                    String widthAttr = element.attr("width");
                    String heightAttr = element.attr("height");

                    if (widthAttr != null && !widthAttr.isBlank()) {
                        try {
                            node.intrinsicWidth = Float.parseFloat(widthAttr);
                        } catch (NumberFormatException ignored) {}
                    }

                    if (heightAttr != null && !heightAttr.isBlank()) {
                        try {
                            node.intrinsicHeight = Float.parseFloat(heightAttr);
                        } catch (NumberFormatException ignored) {}
                    }

                    // Fallback to 640x360 if not specified
                    if (node.intrinsicWidth <= 0) node.intrinsicWidth = 640;
                    if (node.intrinsicHeight <= 0) node.intrinsicHeight = 360;

                    if (node.videoUrl != null && !node.videoUrl.isEmpty()) {
                        Logger.info("PARSER FOUND VIDEO URL: {} ({}x{})",
                                node.videoUrl, node.intrinsicWidth, node.intrinsicHeight);
                    } else {
                        Logger.warn("PARSER FAILED TO FIND VIDEO URL");
                    }

                    node.parent = parent;
                    parent.children.add(node);
                    counters[0]++;
                    build(element, node, depth + 1, counters);
                    continue;
                }

                if (tag.equals("script")) {
                    String scriptContent = element.html();
                    if (scriptContent != null && !scriptContent.isEmpty()) {
                        collectedScripts.append(scriptContent).append("\n");
                    }
                    continue;
                }

                // 1. Handle Inline <style> tags
                if (tag.equals("style")) {
                    String styleContent = element.text();
                    if (styleContent != null && !styleContent.isEmpty()) {
                        collectedCss.append(styleContent).append("\n");
                    }
                    continue;
                }

                // 2. Handle External <link> stylesheets
                if (tag.equals("link")) {
                    if ("stylesheet".equalsIgnoreCase(element.attr("rel"))) {
                        String href = element.attr("href");
                        String fullUrl = pageLoader.resolveUrl(href);
                        Logger.info("Fetching external CSS: {}", fullUrl);
                        String externalCss = pageLoader.downloadCss(fullUrl);
                        collectedCss.append(externalCss).append("\n");
                    }
                    continue;
                }

                if (tag.equals("noscript") || tag.equals("meta")) {
                    continue;
                }

                Node node = new Node();
                node.tag = tag;
                node.type = tag.equals("img") ? Node.Type.IMAGE : Node.Type.ELEMENT;

                // Attribute Handling
                if (element.hasAttr("class")) {
                    for (String className : element.attr("class").split("\\s+")) {
                        node.classes.add(className);
                    }
                }
                if (element.hasAttr("id")) node.id = element.attr("id");

                if (node.type == Node.Type.IMAGE) {
                    node.imageUrl = element.attr("src");
                    node.altText = element.attr("alt");
                    try {
                        if (element.hasAttr("width")) node.width = Float.parseFloat(element.attr("width"));
                        if (element.hasAttr("height")) node.height = Float.parseFloat(element.attr("height"));
                    } catch (NumberFormatException ignored) {}
                }

                if (node.tag.equals("a")) node.href = element.attr("href");

                counters[0]++;
                node.parent = parent;
                parent.children.add(node);
                build(element, node, depth + 1, counters);

            } else {
                String text = child.toString().trim();
                if (!text.isEmpty()) {
                    counters[1]++;
                    Node node = new Node();
                    node.type = Node.Type.TEXT;
                    node.tag = "text";
                    node.text = text;
                    node.parent = parent;
                    parent.children.add(node);
                }
            }
        }
    }

    public void executeScripts(Node root, String script, String currentUrl) {
        Context ctx = Context.enter();
        try {
            Scriptable scope = ctx.initStandardObjects();

            // 1. Create Bridge
            JsNode jsDocument = new JsNode(root);
            ScriptableObject.putProperty(scope, "document", jsDocument);

            java.net.URI uri = java.net.URI.create(currentUrl);

            // 2. Create Window
            Scriptable window = ctx.newObject(scope);

            // 3. Add Location (Prevent the TypeError)
            Scriptable location = ctx.newObject(scope);
            location.put("host", location, uri.getHost());
            location.put("hostname", location, uri.getHost());
            location.put("href", location, currentUrl);

            window.put("location", window, location);
            window.put("innerWidth", window, 1200);
            window.put("innerHeight", window, 800);

            ScriptableObject.putProperty(scope, "window", window);

            ctx.evaluateString(scope, script, "pageScript", 1, null);
        } catch (Exception e) {
            Logger.error("JS Execution failed: {}", e.getMessage());
        } finally {
            Context.exit();
        }
    }
}