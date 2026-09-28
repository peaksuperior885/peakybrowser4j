package com.peak885.peakybrowser4jv2.browser.js;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mozilla.javascript.NativeArray;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class JsDocument {

    private final Document document;
    private final JsNode.Ctx ctx;
    private final AtomicReference<Element> currentScriptRef;
    private final Map<String, List<Object>> listeners = new HashMap<>();

    public JsDocument(Document document, JsNode.Ctx ctx) {
        this(document, ctx, new AtomicReference<>());
    }

    public JsDocument(Document document, JsNode.Ctx ctx, AtomicReference<Element> currentScriptRef) {
        this.document = document;
        this.ctx = ctx;
        this.currentScriptRef = currentScriptRef;
    }

    /** document.getElementsByTagName — several challenge/bootstrap scripts (e.g. Cloudflare's)
     *  reach for this directly on `document` before ever touching an element. */
    public JsNode[] getElementsByTagName(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return new JsNode[0];
        }

        Elements elements = "*".equals(tagName)
                ? document.getAllElements()
                : document.getElementsByTag(tagName);

        JsNode[] result = new JsNode[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            result[i] = ctx.wrap(elements.get(i));
        }
        return result;
    }

    /** document.currentScript — set while executeInlineScripts() is running a given <script>. */
    public JsNode getCurrentScript() {
        Element script = currentScriptRef.get();
        return script == null ? null : ctx.wrap(script);
    }

    public String getTitle() {
        return document.title();
    }

    public void setTitle(String title) {
        document.title(title == null ? "" : title);
    }

    /** Always returns a body node — creates one if missing. */
    public JsNode getBody() {
        Element body = document.body();
        if (body == null) {
            body = document.appendElement("body");
        }
        return ctx.wrap(body);
    }

    /** Always returns a head node — creates one if missing. */
    public JsNode getHead() {
        Element head = document.head();
        if (head == null) {
            head = document.prependElement("head");
        }
        return ctx.wrap(head);
    }

    public JsNode getDocumentElement() {
        return ctx.wrap(document);
    }

    public JsNode getElementById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Element element = document.getElementById(id);
        return element == null ? null : ctx.wrap(element);
    }

    public JsNode querySelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        Element element = document.selectFirst(selector);
        return element == null ? null : ctx.wrap(element);
    }

    public Object querySelectorAll(String selector) {
        if (selector == null || selector.isBlank()) {
            return new NativeArray(0);
        }

        Elements elements = document.select(selector);

        Object[] nodes = new Object[elements.size()];

        for (int i = 0; i < elements.size(); i++) {
            nodes[i] = ctx.wrap(elements.get(i));
        }

        return new NativeArray(nodes);
    }

    public JsNode createElement(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return null;
        }
        // Use the document base URI so relative href/src on dynamically
        // created <link>/<script>/<img> resolve correctly via absUrl().
        Element element = new Element(
                org.jsoup.parser.Tag.valueOf(tagName),
                document.baseUri() != null ? document.baseUri() : ""
        );
        return ctx.wrap(element);
    }

    /**
     * document.createTextNode — required by sites (notably google.com) that
     * inject CSS via:
     *   var s = document.createElement('style');
     *   s.appendChild(document.createTextNode(cssText));
     *
     * Text nodes are faked as a marker element; {@link JsNode#appendChild}
     * expands them into a real TextNode or DataNode (for style/script).
     */
    public JsNode createTextNode(String data) {
        Element holder = new Element(
                org.jsoup.parser.Tag.valueOf("#text"),
                document.baseUri() != null ? document.baseUri() : ""
        );
        holder.attr(JsNode.TEXT_NODE_ATTR, data == null ? "" : data);
        return ctx.wrap(holder);
    }

    /** Convenience alias some scripts use. */
    public JsNode createTextNode() {
        return createTextNode("");
    }

    public void addEventListener(String type, Object listener) {
        addEventListener(type, listener, false);
    }

    /**
     * DOM Level 2 signature: (type, listener, useCapture).
     * Google and others call this with three args; Rhino requires an exact
     * Java overload or it throws "Can't find method ... (string,function,boolean)".
     */
    public void addEventListener(String type, Object listener, boolean useCapture) {
        if (type == null || listener == null) {
            return;
        }
        // useCapture is accepted for API compatibility; we don't distinguish phases yet.
        listeners.computeIfAbsent(type, k -> new ArrayList<>()).add(listener);
    }

    /** Options-object form: addEventListener(type, listener, { capture: true }). */
    public void addEventListener(String type, Object listener, Object options) {
        boolean capture = false;
        if (options instanceof Boolean b) {
            capture = b;
        }
        addEventListener(type, listener, capture);
    }

    public void removeEventListener(String type, Object listener) {
        List<Object> list = listeners.get(type);
        if (list != null) {
            list.remove(listener);
        }
    }

    public void removeEventListener(String type, Object listener, boolean useCapture) {
        removeEventListener(type, listener);
    }

    public void removeEventListener(String type, Object listener, Object options) {
        removeEventListener(type, listener);
    }

    void dispatchEvent(String type, String key) {
        List<Object> list = listeners.get(type);
        if (list == null || list.isEmpty()) {
            return;
        }

        JsNode target = getBody();
        JsNode.JsEvent event = new JsNode.JsEvent(type, target, key);

        for (Object listener : new ArrayList<>(list)) {
            try {
                ctx.invoker().invoke(listener, event);
            } catch (Exception e) {
                Logger.warn(e, "Unhandled error in JS document '{}' listener", type);
            }
        }
    }

    public void dispatchDOMContentLoaded() {
        dispatchEvent("DOMContentLoaded", null);
    }

    /**
     * document.fonts — FontFaceSet API used by modern sites (notably google.com)
     * to preload webfonts via {@code document.fonts.load(...)}.  We do not render
     * custom fonts yet, so this is a no-op stub that returns a resolved Promise
     * so scripts do not throw "Cannot call method load of undefined".
     */
    public Object getFonts() {
        return fontsStub;
    }

    /** Lazily built FontFaceSet-compatible stub (set by JsBridge after init). */
    Object fontsStub;
}