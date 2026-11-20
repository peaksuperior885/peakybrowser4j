package com.peak885.peakybrowser4jv2.browser.js;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.IdentityHashMap;
import java.util.Map;

public final class JsNode implements ExpandoHost {

    // Script-assigned expando properties live here (shared by every Rhino wrapper of this node).
    private final java.util.Map<String, Object> expandos =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    @Override
    public java.util.Map<String, Object> expandoMap() {
        return expandos;
    }

    // Marks a synthetic container Element created by attachShadow() so we can
    // recognize it later (getShadowRoot(), isJsShadowRootNode()) without needing
    // a real Shadow DOM implementation from Jsoup, which doesn't have one.
    private static final String SHADOW_ROOT_ATTR = "data-jsnode-shadow-root";

    /**
     * Marker attribute for fake text nodes produced by
     * {@link JsDocument#createTextNode(String)}. Value is the text data.
     * Package-visible so JsDocument can set it.
     */
    static final String TEXT_NODE_ATTR = "data-jsnode-text";

    private final Element element;
    private final Ctx ctx;
    private final JsEventTarget events;
    private CanvasRenderingContext2D canvas;

    JsNode(Element element, Ctx ctx) {
        this.element = element;
        this.ctx = ctx;
        this.events = new JsEventTarget(this, ctx);
    }

    private void invalidate() {
        ctx.invalidate();
    }

    public synchronized CanvasRenderingContext2D getContext(String type) {
        if (!"canvas".equalsIgnoreCase(element.tagName()) || !"2d".equalsIgnoreCase(type)) return null;
        if (canvas == null) canvas = CanvasRenderingContext2D.forElement(element, this::invalidate);
        return canvas;
    }

    public int getWidth() {
        if (!"canvas".equalsIgnoreCase(element.tagName())) return 0;
        return getContext("2d").getWidth();
    }

    public void setWidth(int width) {
        if ("canvas".equalsIgnoreCase(element.tagName())) getContext("2d").setWidth(width);
        element.attr("width", Integer.toString(Math.max(0, width)));
        invalidate();
    }

    public int getHeight() {
        if (!"canvas".equalsIgnoreCase(element.tagName())) return 0;
        return getContext("2d").getHeight();
    }

    public void setHeight(int height) {
        if ("canvas".equalsIgnoreCase(element.tagName())) getContext("2d").setHeight(height);
        element.attr("height", Integer.toString(Math.max(0, height)));
        invalidate();
    }

    // ============================================================
    // Identity
    // ============================================================

    public String getId() {
        return element.id();
    }

    public void setId(String id) {
        element.attr("id", id == null ? "" : id);
        invalidate();
    }

    public String getClassName() {
        return element.className();
    }

    public void setClassName(String className) {
        element.attr("class", className == null ? "" : className);
        invalidate();
    }

    public String getTagName() {
        if (isFakeTextNode()) {
            return "#text";
        }
        return element.tagName().toUpperCase();
    }

    public String getNodeName() {
        if (isFakeTextNode()) {
            return "#text";
        }
        return getTagName();
    }

    public String getLocalName() {
        if (isFakeTextNode()) {
            return "#text";
        }
        return element.tagName();
    }

    // ============================================================
    // Content
    // ============================================================

    public String getTextContent() {
        if (isFakeTextNode()) {
            return element.attr(TEXT_NODE_ATTR);
        }
        // For <style>/<script>, .data() is the authoritative content
        // (DataNodes); .text() returns empty on those tags in Jsoup.
        String tag = element.tagName();
        if ("style".equals(tag) || "script".equals(tag)) {
            return element.data();
        }
        return element.text();
    }

    public void setTextContent(String text) {
        if (isFakeTextNode()) {
            element.attr(TEXT_NODE_ATTR, text == null ? "" : text);
            invalidate();
            return;
        }
        String t = text == null ? "" : text;
        String tag = element.tagName();
        // style/script content must live in DataNodes so StyleResolver's
        // collectAuthorCss (which reads style.data()) and script loaders
        // see it. element.text() does the right thing for style in recent
        // Jsoup, but using DataNode explicitly is robust across versions
        // and matches how the HTML parser stores these tags.
        if ("style".equals(tag) || "script".equals(tag)) {
            element.empty();
            element.appendChild(new org.jsoup.nodes.DataNode(t));
        } else {
            element.text(t);
        }
        invalidate();
    }

    public String getInnerText() {
        return getTextContent();
    }

    public void setInnerText(String text) {
        setTextContent(text);
    }

    public String getNodeValue() {
        if (isFakeTextNode()) {
            return element.attr(TEXT_NODE_ATTR);
        }
        String tag = element.tagName();
        if ("style".equals(tag) || "script".equals(tag)) {
            return element.data();
        }
        return null;
    }

    public void setNodeValue(String value) {
        if (isFakeTextNode()) {
            element.attr(TEXT_NODE_ATTR, value == null ? "" : value);
            invalidate();
            return;
        }
        String tag = element.tagName();
        if ("style".equals(tag) || "script".equals(tag)) {
            setTextContent(value);
        }
    }

    private boolean isFakeTextNode() {
        return element.hasAttr(TEXT_NODE_ATTR)
                || "#text".equals(element.tagName());
    }

    public String getInnerHTML() {
        return element.html();
    }

    public void setInnerHTML(String html) {
        logDataImageMutation("innerHTML", html);
        element.html(html == null ? "" : html);
        invalidate();
    }

    public String getOuterHTML() {
        return element.outerHtml();
    }

    /*
     * Replaces this element with the first/multiple elements
     * contained in the supplied HTML fragment.
     */
    public void setOuterHTML(String html) {
        if (html == null || element.parent() == null) {
            return;
        }

        org.jsoup.nodes.Document fragment =
                Jsoup.parseBodyFragment(html, element.baseUri());

        Elements replacements = fragment.body().children();

        if (replacements.isEmpty()) {
            element.remove();
            invalidate();
            return;
        }

        for (Element replacement : replacements) {
            element.before(replacement.clone());
        }

        element.remove();
        invalidate();
    }

    // ============================================================
    // Common element properties
    // ============================================================

    public String getValue() {
        if (element.hasAttr("value")) {
            return element.attr("value");
        }

        return element.val();
    }

    public void setValue(String value) {
        String v = value == null ? "" : value;

        element.val(v);
        element.attr("value", v);

        invalidate();
    }

    public String getName() {
        return element.attr("name");
    }

    public void setName(String name) {
        setOrRemoveAttribute("name", name);
    }

    public String getType() {
        return element.attr("type");
    }

    public void setType(String type) {
        setOrRemoveAttribute("type", type);
    }

    public boolean getDisabled() {
        return element.hasAttr("disabled");
    }

    public void setDisabled(boolean disabled) {
        setBooleanAttribute("disabled", disabled);
    }

    public boolean getChecked() {
        return element.hasAttr("checked");
    }

    public void setChecked(boolean checked) {
        setBooleanAttribute("checked", checked);
    }

    public boolean getSelected() {
        return element.hasAttr("selected");
    }

    public void setSelected(boolean selected) {
        setBooleanAttribute("selected", selected);
    }

    public boolean getHidden() {
        return element.hasAttr("hidden");
    }

    public void setHidden(boolean hidden) {
        setBooleanAttribute("hidden", hidden);
    }

    public boolean getMultiple() {
        return element.hasAttr("multiple");
    }

    public void setMultiple(boolean multiple) {
        setBooleanAttribute("multiple", multiple);
    }

    public boolean getRequired() {
        return element.hasAttr("required");
    }

    public void setRequired(boolean required) {
        setBooleanAttribute("required", required);
    }

    public boolean getReadonly() {
        return element.hasAttr("readonly");
    }

    public void setReadonly(boolean readonly) {
        setBooleanAttribute("readonly", readonly);
    }

    public String getTitle() {
        return element.attr("title");
    }

    public void setTitle(String title) {
        setOrRemoveAttribute("title", title);
    }

    public String getLang() {
        return element.attr("lang");
    }

    public void setLang(String lang) {
        setOrRemoveAttribute("lang", lang);
    }

    // ============================================================
    // URL / resource properties
    // ============================================================

    public String getSrc() {
        return element.attr("src");
    }

    public void setSrc(String src) {
        logDataImageMutation("src", src);
        setOrRemoveAttribute("src", src);
    }

    public String getHref() {
        return element.attr("href");
    }

    public void setHref(String href) {
        setOrRemoveAttribute("href", href);
    }

    public String getRel() {
        return element.attr("rel");
    }

    public void setRel(String rel) {
        setOrRemoveAttribute("rel", rel);
    }

    public String getAs() {
        return element.attr("as");
    }

    public void setAs(String as) {
        setOrRemoveAttribute("as", as);
    }

    public String getAction() {
        return element.attr("action");
    }

    public void setAction(String action) {
        setOrRemoveAttribute("action", action);
    }

    public String getMethod() {
        return element.attr("method");
    }

    public void setMethod(String method) {
        setOrRemoveAttribute("method", method);
    }

    /*
     * nonce belongs to the ELEMENT.
     *
     * This is intentionally NOT part of JsClassList.
     */
    public String getNonce() {
        return element.attr("nonce");
    }

    public void setNonce(String nonce) {
        setOrRemoveAttribute("nonce", nonce);
    }

    // ============================================================
    // Attributes
    // ============================================================

    public String getAttribute(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // Browsers return null (not "") when the attribute is absent.
        // reCAPTCHA and many scripts rely on the null/empty distinction.
        return element.hasAttr(name)
                ? element.attr(name)
                : null;
    }

    public JsDataset getDataset() {
        return new JsDataset(element, this::invalidate);
    }

    public static final class JsDataset {

        private final Element element;
        private final Runnable invalidator;

        JsDataset(Element element, Runnable invalidator) {
            this.element = element;
            this.invalidator = invalidator;
        }

        public String getTab() {
            return element.attr("data-tab");
        }

        public void setTab(String value) {
            if (value == null) {
                element.removeAttr("data-tab");
            } else {
                element.attr("data-tab", value);
            }

            invalidator.run();
        }
    }

    public void setAttribute(String name, String value) {
        if ("src".equalsIgnoreCase(name) || "style".equalsIgnoreCase(name)) {
            logDataImageMutation(name, value);
        }
        if (name == null || name.isBlank()) {
            return;
        }

        element.attr(name, value == null ? "" : value);
        invalidate();
    }

    private void logDataImageMutation(String property, String value) {
        if (value == null || !value.toLowerCase(java.util.Locale.ROOT).contains("data:image/")) return;
        boolean malformedMarker = value.contains("iVBORw0KGgBX");
        String classes = element.className();
        org.tinylog.Logger.info(
                "[IMAGE-DOM] {} set on <{} id={} class={}> containing data image URI(s); malformed-PNG-marker={}",
                property, element.tagName(), element.id(), classes, malformedMarker);
    }

    public void removeAttribute(String name) {
        if (name == null || name.isBlank()) {
            return;
        }

        element.removeAttr(name);
        invalidate();
    }

    public boolean hasAttribute(String name) {
        return name != null
                && !name.isBlank()
                && element.hasAttr(name);
    }

    private void setOrRemoveAttribute(String name, String value) {
        if (value == null) {
            element.removeAttr(name);
        } else {
            element.attr(name, value);
        }

        invalidate();
    }

    private void setBooleanAttribute(String name, boolean value) {
        if (value) {
            element.attr(name, "");
        } else {
            element.removeAttr(name);
        }

        invalidate();
    }

    // ============================================================
    // Style / classList
    // ============================================================

    public JsStyle getStyle() {
        return new JsStyle(element, this::invalidate);
    }

    public JsClassList getClassList() {
        return new JsClassList(element, this::invalidate);
    }

    // ============================================================
    // Tree
    // ============================================================

    public JsNode getParentElement() {
        Element parent = element.parent();

        return parent == null
                ? null
                : ctx.wrap(parent);
    }

    public JsNode getParentNode() {
        return getParentElement();
    }

    public JsNode getFirstChild() {
        Elements children = element.children();

        return children.isEmpty()
                ? null
                : ctx.wrap(children.first());
    }

    public JsNode getLastChild() {
        Elements children = element.children();

        return children.isEmpty()
                ? null
                : ctx.wrap(children.last());
    }

    public JsNode getFirstElementChild() {
        return getFirstChild();
    }

    public JsNode getLastElementChild() {
        return getLastChild();
    }

    public JsNode getNextElementSibling() {
        Element next = element.nextElementSibling();

        return next == null
                ? null
                : ctx.wrap(next);
    }

    public JsNode getPreviousElementSibling() {
        Element previous = element.previousElementSibling();

        return previous == null
                ? null
                : ctx.wrap(previous);
    }

    public Object getChildren() {
        return ctx.toJsNodeArray(wrapAll(element.children()));
    }

    public int getChildElementCount() {
        return element.childrenSize();
    }

    public boolean hasChildNodes() {
        return element.childNodeSize() > 0;
    }

    /**
     * Called after a node has been attached to this element. Dynamically added
     * <script> elements must run (that's how loaders like reCAPTCHA's
     * enterprise.js pull in their real code), and iframe insertions are logged
     * so we can see what a page is trying to embed.
     */
    private void notifyInserted(Element inserted) {
        if (inserted == null || inserted.ownerDocument() == null) {
            return; // still detached from the live document
        }
        String tag = inserted.tagName();
        if ("script".equalsIgnoreCase(tag)) {
            ctx.scriptInserted(inserted);
        } else if ("iframe".equalsIgnoreCase(tag)) {
            org.tinylog.Logger.info("[DOM] iframe inserted: src={} attrs={}",
                    inserted.attr("src"), inserted.attributes());
        }
    }

    /** node.insertBefore(newNode, referenceNode) - a null reference appends. */
    /**
     * Fallback overloads for calls where a script passes something that is not one of our
     * nodes (Rhino then reports "Can't find method JsNode.insertBefore(function,null)" and
     * aborts the whole script). Log what was passed - the function's source usually shows
     * which Closure object the page code mistook for an element - and carry on, as a browser
     * would with a thrown-and-caught TypeError.
     */
    public JsNode insertBefore(Object newNode, Object referenceNode) {
        if (newNode instanceof JsNode n && (referenceNode == null || referenceNode instanceof JsNode)) {
            return insertBefore(n, (JsNode) referenceNode);
        }
        logBadNodeArgument("insertBefore", newNode);
        return null;
    }

    public JsNode appendChild(Object child) {
        if (child instanceof JsNode n) {
            return appendChild(n);
        }
        logBadNodeArgument("appendChild", child);
        return null;
    }

    private void logBadNodeArgument(String method, Object argument) {
        String description;
        if (argument == null) {
            description = "null";
        } else if (argument instanceof org.mozilla.javascript.Function fn) {
            String source;
            try {
                source = org.mozilla.javascript.Context.toString(fn);
            } catch (RuntimeException e) {
                source = "(unprintable)";
            }
            if (source.length() > 500) {
                source = source.substring(0, 500) + "...(cut)";
            }
            String name = fn instanceof org.mozilla.javascript.BaseFunction bf ? bf.getFunctionName() : "";
            description = "JS function '" + name + "': " + source;
        } else {
            description = argument.getClass().getName() + ": " + argument;
        }
        org.tinylog.Logger.warn("[DOM] <{}>.{}() was given a non-node argument; ignoring. Argument = {}",
                element.tagName(), method, description);
    }

    private JsNode insertBefore(JsNode newNode, JsNode referenceNode) {
        if (newNode == null) {
            return null;
        }
        if (referenceNode == null || referenceNode.element.parent() != element) {
            return appendChild(newNode);
        }

        int index = referenceNode.element.siblingIndex();

        if (newNode.isFakeTextNode()) {
            String text = newNode.element.attr(TEXT_NODE_ATTR);
            String tag = element.tagName();
            Node textNode = ("style".equals(tag) || "script".equals(tag))
                    ? new org.jsoup.nodes.DataNode(text)
                    : new org.jsoup.nodes.TextNode(text);
            element.insertChildren(index, textNode);
            invalidate();
            return newNode;
        }

        element.insertChildren(index, newNode.element);
        invalidate();
        notifyInserted(newNode.element);
        return newNode;
    }

    private JsNode appendChild(JsNode child) {
        if (child == null) {
            return null;
        }

        if (child.isFakeTextNode()) {
            String text = child.element.attr(TEXT_NODE_ATTR);
            String tag = element.tagName();
            if ("style".equals(tag) || "script".equals(tag)) {
                element.appendChild(new org.jsoup.nodes.DataNode(text));
            } else {
                element.appendText(text);
            }
            invalidate();
            return child;
        }

        element.appendChild(child.element);
        invalidate();
        notifyInserted(child.element);

        return child;
    }

    public JsNode removeChild(JsNode child) {
        if (child == null) {
            return null;
        }

        if (child.element.parent() == element) {
            child.element.remove();
            invalidate();
        }

        return child;
    }

    public void remove() {
        element.remove();
        invalidate();
    }

    // ============================================================
    // Shadow DOM (fake — Jsoup has no real Shadow DOM). Good enough
    // to stop scripts crashing on attachShadow()/.shadowRoot; content
    // put inside is still just a normal (if unstyled-by-default) child
    // subtree, not actually encapsulated from outside CSS/selectors.
    // ============================================================

    public JsNode attachShadow(Object init) {
        Element shadowContainer = new Element(Tag.valueOf("shadow-root"), element.baseUri());
        shadowContainer.attr(SHADOW_ROOT_ATTR, "true");
        element.appendChild(shadowContainer);
        invalidate();
        return ctx.wrap(shadowContainer);
    }

    public JsNode getShadowRoot() {
        for (Element child : element.children()) {
            if (child.hasAttr(SHADOW_ROOT_ATTR)) {
                return ctx.wrap(child);
            }
        }
        return null;
    }

    public boolean isJsShadowRootNode() {
        return element.hasAttr(SHADOW_ROOT_ATTR);
    }

    public boolean contains(JsNode other) {
        if (other == null) {
            return false;
        }

        Element current = other.element;

        while (current != null) {
            if (current == element) {
                return true;
            }

            current = current.parent();
        }

        return false;
    }

    // ============================================================
    // Querying
    // ============================================================

    public JsNode querySelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }

        Element found = element.selectFirst(selector);

        return found == null
                ? null
                : ctx.wrap(found);
    }

    public Object querySelectorAll(String selector) {
        if (selector == null || selector.isBlank()) {
            return ctx.toJsNodeArray(new JsNode[0]);
        }

        Elements elements = element.select(selector);
        return ctx.toJsNodeArray(wrapAll(elements));
    }

    public Object getElementsByTagName(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return ctx.toJsNodeArray(new JsNode[0]);
        }

        Elements elements;

        if ("*".equals(tagName)) {
            elements = element.getAllElements();
        } else {
            elements = element.getElementsByTag(tagName);
        }

        return ctx.toJsNodeArray(wrapAll(elements));
    }

    public Object getElementsByClassName(String className) {
        if (className == null || className.isBlank()) {
            return ctx.toJsNodeArray(new JsNode[0]);
        }

        return ctx.toJsNodeArray(wrapAll(
                element.getElementsByClass(className)
        ));
    }

    public JsNode getElementById(String id) {
        if (id == null || element.ownerDocument() == null) {
            return null;
        }

        Element found =
                element.ownerDocument().getElementById(id);

        return found == null
                ? null
                : ctx.wrap(found);
    }

    public boolean matches(String selector) {
        if (selector == null || selector.isBlank()) {
            return false;
        }

        return element.is(selector);
    }

    public JsNode closest(String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }

        Element current = element;

        while (current != null) {
            if (current.is(selector)) {
                return ctx.wrap(current);
            }

            current = current.parent();
        }

        return null;
    }

    private JsNode[] wrapAll(Elements elements) {
        JsNode[] result = new JsNode[elements.size()];

        for (int i = 0; i < elements.size(); i++) {
            result[i] = ctx.wrap(elements.get(i));
        }

        return result;
    }

    // ============================================================
    // Events
    // ============================================================

    public void addEventListener(String type, Object listener) {
        events.addEventListener(type, listener, false);
    }

    public void addEventListener(
            String type,
            Object listener,
            boolean useCapture
    ) {
        events.addEventListener(type, listener, useCapture);
    }

    /** Options-object form used by modern sites. */
    public void addEventListener(String type, Object listener, Object options) {
        boolean capture = false;
        if (options instanceof Boolean b) {
            capture = b;
        }
        events.addEventListener(type, listener, capture);
    }

    public void removeEventListener(
            String type,
            Object listener
    ) {
        events.removeEventListener(type, listener);
    }

    public void removeEventListener(String type, Object listener, boolean useCapture) {
        events.removeEventListener(type, listener);
    }

    public void removeEventListener(String type, Object listener, Object options) {
        events.removeEventListener(type, listener);
    }

    public void dispatchEvent(String type) {
        events.dispatchEvent(type);
    }

    public void dispatchEvent(String type, String key) {
        events.dispatchEvent(type, key);
    }

    // ============================================================
    // DOM event properties
    // ============================================================

    public void setOnclick(Object fn) {
        events.setHandler("click", fn);
    }

    public void setOnkeydown(Object fn) {
        events.setHandler("keydown", fn);
    }

    public void setOnkeyup(Object fn) {
        events.setHandler("keyup", fn);
    }

    public void setOninput(Object fn) {
        events.setHandler("input", fn);
    }

    public void setOnchange(Object fn) {
        events.setHandler("change", fn);
    }

    public void setOnfocus(Object fn) {
        events.setHandler("focus", fn);
    }

    public void setOnblur(Object fn) {
        events.setHandler("blur", fn);
    }

    public void setOnload(Object fn) {
        events.setHandler("load", fn);
    }

    public void setOnerror(Object fn) {
        events.setHandler("error", fn);
    }

    public void setOnsubmit(Object fn) {
        events.setHandler("submit", fn);
    }

    public void setOnmousedown(Object fn) {
        events.setHandler("mousedown", fn);
    }

    public void setOnmouseup(Object fn) {
        events.setHandler("mouseup", fn);
    }

    public void setOnmousemove(Object fn) {
        events.setHandler("mousemove", fn);
    }

    public void setOnmouseover(Object fn) {
        events.setHandler("mouseover", fn);
    }

    public void setOnmouseout(Object fn) {
        events.setHandler("mouseout", fn);
    }

    public void focus() {
        dispatchEvent("focus");
    }

    public void blur() {
        dispatchEvent("blur");
    }

    public void click() {
        dispatchEvent("click");
    }

    // ============================================================
    // Internal access
    // ============================================================

    Element getElement() {
        return element;
    }

    Ctx getContext() {
        return ctx;
    }

    // ============================================================
    // Shared DOM context
    // ============================================================

    /*
     * Kept nested inside JsNode because JsBridge already uses:
     *
     *     JsNode.Ctx
     *
     * This is the single source of truth for wrapped nodes.
     */
    public static class Ctx {

        private final Runnable layoutInvalidator;
        private final FunctionInvoker invoker;
        private Scriptable globalScope;

        private JsDocument document;

        public void setDocument(JsDocument document) {
            this.document = document;
        }

        public JsDocument getDocument() {
            return document;
        }

        private final Map<Element, JsNode> cache =
                new IdentityHashMap<>();

        public Ctx(
                Runnable layoutInvalidator,
                FunctionInvoker invoker
        ) {
            this.layoutInvalidator =
                    layoutInvalidator != null
                            ? layoutInvalidator
                            : () -> {};

            this.invoker =
                    invoker != null
                            ? invoker
                            : (listener, event) -> {};
        }

        /** Must be called once after the Rhino global scope is created so node lists
         *  can store properly-wrapped Scriptable values (required for typeof / forEach). */
        public void setGlobalScope(Scriptable scope) {
            this.globalScope = scope;
        }

        public Scriptable getGlobalScope() {
            return globalScope;
        }

        public void invalidate() {
            layoutInvalidator.run();
        }

        public FunctionInvoker invoker() {
            return invoker;
        }

        public JsNode wrap(Element element) {
            if (element == null) {
                return null;
            }

            return cache.computeIfAbsent(
                    element,
                    e -> new JsNode(e, this)
            );
        }

        /**
         * Build a Rhino NativeArray whose elements are already wrapped as
         * Scriptable (via the configured WrapFactory). Putting raw JsNode
         * instances into a NativeArray causes
         * "Invalid JavaScript value of type ...JsNode" when page scripts
         * (notably Closure / reCAPTCHA) call Array.prototype.forEach or
         * typeof on the entries.
         */
        public Object toJsNodeArray(JsNode[] nodes) {
            Context cx = Context.getCurrentContext();
            Scriptable scope = globalScope;

            // Prefer Context.newArray so the result is a real JS Array with
            // Array.prototype / constructor / @@species. Bare `new NativeArray`
            // breaks Array.prototype.filter.call(nodes, …) with:
            //   TypeError: Constructor for "NativeArray@…" not found
            if (nodes == null || nodes.length == 0) {
                if (cx != null && scope != null) {
                    return cx.newArray(scope, 0);
                }
                return new NativeArray(0); // last-resort; no scope available
            }

            Object[] jsValues = new Object[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                JsNode n = nodes[i];
                if (n == null) {
                    jsValues[i] = null;
                } else if (cx != null && scope != null) {
                    // javaToJS goes through the Context's WrapFactory, producing
                    // ExpandoAwareJavaObject so typeof / forEach see a real Scriptable.
                    jsValues[i] = Context.javaToJS(n, scope);
                } else {
                    // Fallback (should not happen after setGlobalScope): keep raw host.
                    jsValues[i] = n;
                }
            }

            if (cx != null && scope != null) {
                return cx.newArray(scope, jsValues);
            }
            return new NativeArray(jsValues);
        }

        public void clearCache() {
            cache.clear();
        }

        // Called when a <script> element is attached to the live document after
        // load (document.createElement('script') + insertBefore/appendChild/...).
        private java.util.function.Consumer<Element> scriptInsertedListener;

        public void setScriptInsertedListener(java.util.function.Consumer<Element> listener) {
            this.scriptInsertedListener = listener;
        }

        void scriptInserted(Element script) {
            if (scriptInsertedListener != null) {
                scriptInsertedListener.accept(script);
            }
        }
    }

    // ============================================================
    // Function invocation
    // ============================================================

    @FunctionalInterface
    public interface FunctionInvoker {

        void invoke(
                Object listener,
                Object event
        );
    }

    // ============================================================
    // Event
    // ============================================================

    /*
     * Also deliberately kept nested because JsBridge currently
     * references JsNode.JsEvent.
     */
    public static final class JsEvent {

        private final String type;
        private final JsNode target;
        private final String key;

        private boolean defaultPrevented;
        private boolean propagationStopped;

        public JsEvent(
                String type,
                JsNode target,
                String key
        ) {
            this.type = type == null ? "" : type;
            this.target = target;
            this.key = key;
        }

        public String getType() {
            return type;
        }

        public JsNode getTarget() {
            return target;
        }

        public JsNode getCurrentTarget() {
            return target;
        }

        public JsNode getSrcElement() {
            return target;
        }

        public String getKey() {
            return key == null ? "" : key;
        }

        public int getKeyCode() {
            return 0;
        }

        public int getWhich() {
            return 0;
        }

        public int getButton() {
            return 0;
        }

        public int getClientX() {
            return 0;
        }

        public int getClientY() {
            return 0;
        }

        public int getPageX() {
            return 0;
        }

        public int getPageY() {
            return 0;
        }

        public boolean isBubbles() {
            return true;
        }

        public boolean isCancelable() {
            return true;
        }

        public void preventDefault() {
            defaultPrevented = true;
        }

        public void stopPropagation() {
            propagationStopped = true;
        }

        public boolean isDefaultPrevented() {
            return defaultPrevented;
        }

        public boolean isPropagationStopped() {
            return propagationStopped;
        }
    }

    // ============================================================
// Modern DOM methods (append / prepend / replaceChildren)
// ============================================================

    public void append(Object... nodes) {
        if (nodes == null) return;
        boolean any = false;
        for (Object node : nodes) {
            if (node instanceof JsNode jsNode) {
                // appendChild already invalidates; track so we don't
                // double-fire when only JsNodes are passed.
                if (jsNode.isFakeTextNode()) {
                    String text = jsNode.element.attr(TEXT_NODE_ATTR);
                    appendCssAwareText(text);
                    any = true;
                } else {
                    element.appendChild(jsNode.element);
                    notifyInserted(jsNode.element);
                    any = true;
                }
            } else if (node != null) {
                appendCssAwareText(Context.toString(node));
                any = true;
            }
        }
        if (any) {
            invalidate();
        }
    }

    public void prepend(Object... nodes) {
        if (nodes == null) return;
        // insert in reverse so the final order is correct
        for (int i = nodes.length - 1; i >= 0; i--) {
            Object node = nodes[i];
            if (node instanceof JsNode jsNode) {
                if (jsNode.isFakeTextNode()) {
                    String text = jsNode.element.attr(TEXT_NODE_ATTR);
                    prependCssAwareText(text);
                } else {
                    element.insertChildren(0, jsNode.element);
                    notifyInserted(jsNode.element);
                }
            } else if (node != null) {
                prependCssAwareText(Context.toString(node));
            }
        }
        invalidate();
    }

    private void appendCssAwareText(String text) {
        String tag = element.tagName();
        if ("style".equals(tag) || "script".equals(tag)) {
            element.appendChild(new org.jsoup.nodes.DataNode(text));
        } else {
            element.appendText(text);
        }
    }

    private void prependCssAwareText(String text) {
        String tag = element.tagName();
        if ("style".equals(tag) || "script".equals(tag)) {
            element.insertChildren(0, new org.jsoup.nodes.DataNode(text));
        } else {
            element.insertChildren(0, new org.jsoup.nodes.TextNode(text));
        }
    }

    public void replaceChildren(Object... nodes) {
        element.children().remove();          // clear existing element children
        // also clear text nodes if you want full compatibility
        element.textNodes().forEach(Node::remove);
        append(nodes);                        // reuse append
    }

    public JsNode createDocumentFragment() {
        // Jsoup has no real DocumentFragment, so we fake it with a plain <div>
        // that is never attached to the document.
        Element frag = new Element(Tag.valueOf("div"), "");
        frag.attr("data-jsnode-fragment", "true");
        return ctx.wrap(frag);
    }

    // in JsNode
    public JsDocument getOwnerDocument() {
        JsDocument doc = ctx.getDocument();
        if (doc == null) {
            org.tinylog.Logger.warn("[DOM] ownerDocument == null for <{}> (tag={})",
                    System.identityHashCode(this), element.tagName());
        }
        return doc;
    }

    public int getNodeType() {
        if (isFakeTextNode()) {
            return 3; // TEXT_NODE
        }
        // Document nodes must report 9, otherwise Closure’s getOwnerDocument breaks
        if (element instanceof org.jsoup.nodes.Document) {
            return 9; // DOCUMENT_NODE
        }
        return 1; // ELEMENT_NODE
    }

    public Object getChildNodes() {
        Context cx = Context.getCurrentContext();
        Scriptable scope = ctx.getGlobalScope();

        Object[] nodes = element.childNodes().stream()
                .map(node -> {
                    if (node instanceof Element child) {
                        return ctx.wrap(child);
                    }

                    JsNode textNode = ctx.wrap(element);
                    textNode.setAttribute(TEXT_NODE_ATTR, "true");
                    textNode.setTextContent(node.toString());
                    return textNode;
                })
                .toArray();

        if (cx != null && scope != null) {
            Object[] jsValues = new Object[nodes.length];

            for (int i = 0; i < nodes.length; i++) {
                jsValues[i] = Context.javaToJS(nodes[i], scope);
            }

            return cx.newArray(scope, jsValues);
        }

        return new NativeArray(nodes);
    }
}
