package com.peak885.peakybrowser4jv2.browser.js;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;

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

    JsNode(Element element, Ctx ctx) {
        this.element = element;
        this.ctx = ctx;
        this.events = new JsEventTarget(this, ctx);
    }

    private void invalidate() {
        ctx.invalidate();
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

    public short getNodeType() {
        if (isFakeTextNode()) {
            return 3; // TEXT_NODE
        }
        return 1; // ELEMENT_NODE
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
            return "";
        }

        return element.hasAttr(name)
                ? element.attr(name)
                : "";
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
        if (name == null || name.isBlank()) {
            return;
        }

        element.attr(name, value == null ? "" : value);
        invalidate();
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

    public JsNode[] getChildren() {
        return wrapAll(element.children());
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
    public JsNode insertBefore(JsNode newNode, JsNode referenceNode) {
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

    public JsNode appendChild(JsNode child) {
        if (child == null) {
            return null;
        }

        // Expand fake text nodes from document.createTextNode into real
        // content. Google and many others inject CSS with:
        //   styleEl.appendChild(document.createTextNode(css))
        // For <style>/<script> that content must be a DataNode so
        // StyleResolver.collectAuthorCss (style.data()) sees it.
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
            return new NativeArray(0);
        }

        Elements elements = element.select(selector);

        Object[] nodes = new Object[elements.size()];

        for (int i = 0; i < elements.size(); i++) {
            nodes[i] = ctx.wrap(elements.get(i));
        }

        return new NativeArray(nodes);
    }

    public JsNode[] getElementsByTagName(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return new JsNode[0];
        }

        Elements elements;

        if ("*".equals(tagName)) {
            elements = element.getAllElements();
        } else {
            elements = element.getElementsByTag(tagName);
        }

        return wrapAll(elements);
    }

    public JsNode[] getElementsByClassName(String className) {
        if (className == null || className.isBlank()) {
            return new JsNode[0];
        }

        return wrapAll(
                element.getElementsByClass(className)
        );
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
}