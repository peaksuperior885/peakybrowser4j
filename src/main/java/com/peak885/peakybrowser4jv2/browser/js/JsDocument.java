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

public final class JsDocument implements ExpandoHost {

    // Script-assigned expando properties (document.__x = ...) shared by every Rhino wrapper of this document.
    private final java.util.Map<String, Object> expandos =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    @Override
    public java.util.Map<String, Object> expandoMap() {
        return expandos;
    }

    private final Document document;
    private final JsNode.Ctx ctx;
    private final AtomicReference<Element> currentScriptRef;
    private final Map<String, List<Object>> listeners = new HashMap<>();

    // document.cookie is backed by the same OkHttp CookieJar the HTTP layer uses,
    // so a cookie a page script sets (e.g. Google's SG_SS) is sent on the next request.
    private okhttp3.CookieJar cookieJar;
    private String cookieBaseUrl;

    public void setCookieAccess(okhttp3.CookieJar jar, String baseUrl) {
        this.cookieJar = jar;
        this.cookieBaseUrl = baseUrl;
    }

    /** document.cookie (getter): script-visible (non-HttpOnly) cookies for this document. */
    public String getCookie() {
        if (cookieJar == null || cookieBaseUrl == null) {
            return "";
        }
        try {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(cookieBaseUrl);
            if (url == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (okhttp3.Cookie c : cookieJar.loadForRequest(url)) {
                if (c.httpOnly()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                // Per RFC 6265bis a cookie set without '=' has an empty name and is
                // serialized as just its value.
                if (!c.name().isEmpty()) {
                    sb.append(c.name()).append('=');
                }
                sb.append(c.value());
            }
            return sb.toString();
        } catch (Exception e) {
            Logger.warn(e, "[COOKIE] read failed");
            return "";
        }
    }

    /** document.cookie (setter): takes one Set-Cookie style string. */
    public void setCookie(String raw) {
        if (raw == null || cookieJar == null || cookieBaseUrl == null) {
            return;
        }
        try {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(cookieBaseUrl);
            if (url == null) {
                return;
            }
            // OkHttp's parser is strict (it rejects name-less cookies, odd Domain
            // attributes, ...). Browsers are not, so fall back to a lenient parse
            // instead of silently dropping cookies like Google's SG_SS.
            // Browsers reject cookies containing control characters (other than tab).
            // Google's challenge writes SG_SS=E:<js error + stack with CR/LF> when it
            // fails; sending that back would make OkHttp throw on the Cookie header.
            String pair = raw.contains(";") ? raw.substring(0, raw.indexOf(';')) : raw;
            if (hasControlChars(pair)) {
                String preview = pair.length() > 600 ? pair.substring(0, 600) + "..." : pair;
                Logger.warn("[COOKIE] rejected document.cookie write containing control characters ({} chars): {}",
                        raw.length(), escapeControls(preview));
                return;
            }
            okhttp3.Cookie cookie = okhttp3.Cookie.parse(url, raw);
            if (cookie == null) {
                cookie = parseLenient(url, raw);
            }
            if (cookie == null) {
                int eq = raw.indexOf('=');
                int semi = raw.indexOf(';');
                String name = eq >= 0 && (semi < 0 || eq < semi) ? raw.substring(0, eq).trim() : "";
                Logger.warn("[COOKIE] rejected document.cookie write (name='{}', {} chars)", name, raw.length());
                return;
            }
            cookieJar.saveFromResponse(url, java.util.List.of(cookie));
            Logger.info("[COOKIE] document.cookie set: {} ({} chars)", cookie.name(), cookie.value().length());
        } catch (Exception e) {
            Logger.warn(e, "[COOKIE] write failed");
        }
    }

    static boolean hasControlChars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String escapeControls(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Forgiving document.cookie parser used when OkHttp's strict {@link okhttp3.Cookie#parse} says no. */
    private static okhttp3.Cookie parseLenient(okhttp3.HttpUrl url, String raw) {
        String[] parts = raw.split(";");
        if (parts.length == 0) {
            return null;
        }

        String first = parts[0];
        String name;
        String value;
        int eq = first.indexOf('=');
        if (eq < 0) {
            name = "";
            value = first.trim();
        } else {
            name = first.substring(0, eq).trim();
            value = first.substring(eq + 1).trim();
        }
        if (name.isEmpty() && value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return null;
            }
        }

        String domain = null;
        String path = null;
        long expiresAt = Long.MAX_VALUE;
        boolean expiresSet = false;
        boolean maxAgeSet = false;
        boolean secure = false;
        boolean httpOnly = false;

        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            if (attr.isEmpty()) {
                continue;
            }
            int aeq = attr.indexOf('=');
            String key = (aeq < 0 ? attr : attr.substring(0, aeq)).trim().toLowerCase(java.util.Locale.ROOT);
            String val = aeq < 0 ? "" : attr.substring(aeq + 1).trim();
            switch (key) {
                case "domain" -> {
                    if (!val.isEmpty()) {
                        domain = val.startsWith(".") ? val.substring(1) : val;
                        domain = domain.toLowerCase(java.util.Locale.ROOT);
                    }
                }
                case "path" -> {
                    if (val.startsWith("/")) {
                        path = val;
                    }
                }
                case "max-age" -> {
                    try {
                        long seconds = Long.parseLong(val);
                        expiresAt = seconds <= 0
                                ? 1L
                                : System.currentTimeMillis() + seconds * 1000L;
                        maxAgeSet = true;
                    } catch (NumberFormatException ignored) {
                        // ignore a malformed Max-Age
                    }
                }
                case "expires" -> {
                    if (!maxAgeSet) { // Max-Age wins over Expires
                        Long parsed = parseCookieDate(val);
                        if (parsed != null) {
                            expiresAt = parsed;
                            expiresSet = true;
                        }
                    }
                }
                case "secure" -> secure = true;
                case "httponly" -> httpOnly = true; // script can't set HttpOnly, but keep the flag honest
                default -> { /* SameSite, Priority, Partitioned, ... - not modelled by OkHttp's Cookie */ }
            }
        }

        // A script may only set cookies for its own host or a parent domain of it.
        String host = url.host();
        if (domain != null && !(host.equals(domain) || host.endsWith("." + domain))) {
            return null;
        }

        try {
            okhttp3.Cookie.Builder builder = new okhttp3.Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path != null ? path : defaultCookiePath(url));

            if (domain != null) {
                builder.domain(domain);
            } else {
                builder.hostOnlyDomain(host);
            }
            if (maxAgeSet || expiresSet) {
                builder.expiresAt(expiresAt);
            }
            if (secure) {
                builder.secure();
            }
            if (httpOnly) {
                builder.httpOnly();
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            Logger.warn("[COOKIE] lenient parse failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private static String defaultCookiePath(okhttp3.HttpUrl url) {
        String p = url.encodedPath();
        int last = p.lastIndexOf('/');
        return last <= 0 ? "/" : p.substring(0, last);
    }

    private static Long parseCookieDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim().replace("-", " ").replaceAll("\\s+", " ");
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, d MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yy HH:mm:ss zzz",
                "EEEE, dd MMM yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"
        };
        for (String pattern : patterns) {
            try {
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter
                        .ofPattern(pattern, java.util.Locale.ENGLISH)
                        .withZone(java.time.ZoneOffset.UTC);
                return java.time.ZonedDateTime.parse(cleaned, f).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // try the next shape
            }
        }
        return null;
    }

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
    public Object getElementsByTagName(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return new NativeArray(0);
        }

        Elements elements = "*".equals(tagName)
                ? document.getAllElements()
                : document.getElementsByTag(tagName);

        Object[] nodes = new Object[elements.size()];

        for (int i = 0; i < elements.size(); i++) {
            nodes[i] = ctx.wrap(elements.get(i));
        }

        return new NativeArray(nodes);
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