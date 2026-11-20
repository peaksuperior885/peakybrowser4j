package com.peak885.peakybrowser4jv2.browser.js;

import com.peak885.peakybrowser4jv2.browser.DiagnosticPaths;
import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.tinylog.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight XMLHttpRequest implementation for the JavaScript runtime.
 *
 * Supports both classic on* handlers and EventTarget-style listeners
 * (via the EventSink callback installed by the Rhino wrapper).
 * Async mode runs the network work off the Rhino thread and delivers
 * readyState / load / error events afterwards.
 */
public final class JsXMLHttpRequest {

    // ---------------------------------------------------------------------
    // XMLHttpRequest readyState constants
    // ---------------------------------------------------------------------

    public static final int UNSENT = 0;
    public static final int OPENED = 1;
    public static final int HEADERS_RECEIVED = 2;
    public static final int LOADING = 3;
    public static final int DONE = 4;

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    private final HttpManager http;
    private final String baseUrl;

    /** Optional executor used for async=true requests. */
    private Executor asyncExecutor;

    /**
     * Bridge back into the Rhino wrapper so we can fire
     * onreadystatechange / load / error / loadend and also
     * dispatchEvent on the Scriptable instance.
     */
    public interface EventSink {
        void onReadyStateChange(int readyState);
        void onLoad();
        void onError();
        void onLoadEnd();
    }

    private volatile EventSink eventSink;

    // ---------------------------------------------------------------------
    // Request state
    // ---------------------------------------------------------------------

    private String method = "GET";
    private String requestUrl;

    private boolean async = true;
    private boolean withCredentials;

    private int timeout;

    private final Map<String, String> requestHeaders =
            new LinkedHashMap<>();

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicBoolean sendCalled = new AtomicBoolean(false);

    // ---------------------------------------------------------------------
    // Response state
    // ---------------------------------------------------------------------

    private volatile int readyState = UNSENT;

    private volatile int status;
    private volatile String statusText = "";

    private volatile String responseText = "";
    private volatile String responseUrl = "";

    private volatile Map<String, String> responseHeaders =
            Map.of();

    // ---------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------

    public JsXMLHttpRequest(
            HttpManager http,
            String baseUrl
    ) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public void setAsyncExecutor(Executor executor) {
        this.asyncExecutor = executor;
    }

    public void setEventSink(EventSink sink) {
        this.eventSink = sink;
    }

    // ---------------------------------------------------------------------
    // open()
    // ---------------------------------------------------------------------

    public void open(
            String method,
            String url
    ) {
        open(method, url, true);
    }

    public void open(
            String method,
            String url,
            boolean async
    ) {

        this.method =
                method == null
                        ? "GET"
                        : method.toUpperCase(Locale.ROOT);

        this.requestUrl = resolveUrl(url);
        this.async = async;

        this.status = 0;
        this.statusText = "";
        this.responseText = "";
        this.responseUrl = "";
        this.responseHeaders = Map.of();

        this.requestHeaders.clear();
        this.aborted.set(false);
        this.sendCalled.set(false);

        setReadyState(OPENED);

        Logger.info(
                "[XHR] open({}, {}, async={})",
                this.method,
                DiagnosticPaths.safeUrl(this.requestUrl),
                async
        );
    }

    // ---------------------------------------------------------------------
    // setRequestHeader()
    // ---------------------------------------------------------------------

    public void setRequestHeader(
            String name,
            String value
    ) {

        if (name == null || name.isBlank()) {
            Logger.debug("[XHR] Ignoring empty request header name");
            return;
        }

        if (value == null) {
            value = "";
        }

        // Forbidden headers are ignored by real browsers; we keep it simple.
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("host")
                || lower.equals("connection")
                || lower.equals("content-length")
                || lower.startsWith("sec-")) {
            // Allow the page to set most things, but we will still inject
            // our own Sec-Fetch-* below so they win.
        }

        requestHeaders.put(name, value);

        Logger.debug("[XHR] setRequestHeader {}: {}", name, value);
    }

    // ---------------------------------------------------------------------
    // send()
    // ---------------------------------------------------------------------

    public void send() {
        send(null);
    }

    /**
     * Entry point called from the Rhino wrapper.
     * When async==true the network work is scheduled on the asyncExecutor
     * (if present) and this method returns immediately.
     */
    public void send(String body) {

        if (readyState != OPENED) {
            throw new IllegalStateException(
                    "InvalidStateError: XMLHttpRequest must be opened before send()"
            );
        }

        if (!sendCalled.compareAndSet(false, true)) {
            Logger.warn("[XHR] send() called more than once");
            return;
        }

        Logger.info(
                "[XHR] send() method={} url={} bodyLength={} async={}",
                method,
                DiagnosticPaths.safeUrl(requestUrl),
                body == null ? 0 : body.length(),
                async
        );

        if (async && asyncExecutor != null) {
            final String bodyCopy = body;
            asyncExecutor.execute(() -> {
                try {
                    doSend(bodyCopy);
                } catch (Throwable t) {
                    Logger.warn(t, "[XHR] async send failed {}", DiagnosticPaths.safeUrl(requestUrl));
                    if (!aborted.get()) {
                        status = 0;
                        statusText = "";
                        responseText = "";
                        setReadyState(DONE);
                        fireError();
                        fireLoadEnd();
                    }
                }
            });
        } else {
            // Synchronous path (or no executor available)
            try {
                doSend(body);
            } catch (Throwable t) {
                Logger.warn(t, "[XHR] sync send failed {}", DiagnosticPaths.safeUrl(requestUrl));
                if (!aborted.get()) {
                    status = 0;
                    statusText = "";
                    responseText = "";
                    setReadyState(DONE);
                    fireError();
                    fireLoadEnd();
                }
            }
        }
    }

    private void doSend(String body) throws IOException {

        if (aborted.get()) {
            return;
        }

        Request.Builder builder =
                new Request.Builder()
                        .url(requestUrl);

        // -------------------------------------------------------------
        // Inject browser-like headers that reCAPTCHA / modern sites expect
        // -------------------------------------------------------------
        injectBrowserHeaders(builder);

        // -------------------------------------------------------------
        // Request headers set by the page
        // -------------------------------------------------------------
        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();

            if (name == null || name.isBlank() || value == null) {
                continue;
            }

            try {
                builder.header(name, value);
            } catch (IllegalArgumentException e) {
                Logger.debug("[XHR] Ignoring invalid header {}", name);
            }
        }

        // -------------------------------------------------------------
        // Method / body
        // -------------------------------------------------------------
        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, null);
        } else {
            String contentType = findHeader(requestHeaders, "Content-Type");

            MediaType mediaType;
            try {
                mediaType = contentType != null
                        ? MediaType.parse(contentType)
                        : MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
            } catch (Exception e) {
                mediaType = MediaType.parse("application/octet-stream");
            }

            RequestBody requestBody = RequestBody.create(
                    body == null ? "" : body,
                    mediaType
            );

            builder.method(method, requestBody);
        }

        // -------------------------------------------------------------
        // Execute
        // -------------------------------------------------------------
        Logger.info("[XHR] >>> {} {}", method, DiagnosticPaths.safeUrl(requestUrl));

        // Transition through the normal states so listeners see them
        setReadyState(HEADERS_RECEIVED);

        if (aborted.get()) {
            return;
        }

        HttpManager.HttpResponse response = http.execute(builder.build());

        if (aborted.get()) {
            return;
        }

        // -------------------------------------------------------------
        // Store response
        // -------------------------------------------------------------
        status = response.getStatusCode();
        statusText = response.getStatusMessage() == null
                ? ""
                : response.getStatusMessage();

        responseText = response.getBodyAsString() == null
                ? ""
                : response.getBodyAsString();

        responseUrl = response.getFinalUrl() == null
                ? requestUrl
                : response.getFinalUrl();

        Map<String, String> headers = response.getHeaders();
        responseHeaders = headers == null
                ? Map.of()
                : new LinkedHashMap<>(headers);

        Logger.info(
                "[XHR] <<< {} {} status={} contentType={} bodyLength={}",
                method,
                DiagnosticPaths.safeUrl(responseUrl),
                status,
                response.getContentType(),
                responseText.length()
        );

        setReadyState(LOADING);
        setReadyState(DONE);

        // Final events
        if (status >= 200 && status < 300) {
            fireLoad();
        } else {
            fireError();
        }
        fireLoadEnd();
    }

    /**
     * Adds Origin + Sec-Fetch-* headers that real browsers send for XHR/fetch.
     * These are critical for Google / reCAPTCHA CORS endpoints.
     */
    private void injectBrowserHeaders(Request.Builder builder) {
        try {
            URI docUri = URI.create(baseUrl != null ? baseUrl : "https://www.google.com/");
            URI reqUri = URI.create(requestUrl);

            String origin = docUri.getScheme() + "://" + docUri.getAuthority();

            // Only add Origin for non-GET or cross-origin (browsers do this)
            boolean sameOrigin = docUri.getScheme().equalsIgnoreCase(reqUri.getScheme())
                    && docUri.getAuthority().equalsIgnoreCase(reqUri.getAuthority());

            if (!"GET".equals(method) && !"HEAD".equals(method) || !sameOrigin) {
                builder.header("Origin", origin);
            }

            // Referer is almost always present
            if (baseUrl != null && !baseUrl.isBlank()) {
                builder.header("Referer", baseUrl);
            }

            // Sec-Fetch metadata – exact values matter to Google
            builder.header("Sec-Fetch-Dest", "empty");
            builder.header("Sec-Fetch-Mode", "cors");
            builder.header("Sec-Fetch-Site", sameOrigin ? "same-origin" : "cross-site");

            // Helpful Accept defaults if the page didn't set them
            if (findHeader(requestHeaders, "Accept") == null) {
                builder.header("Accept", "*/*");
            }
            if (findHeader(requestHeaders, "Accept-Language") == null) {
                builder.header("Accept-Language", "en-US,en;q=0.9");
            }

        } catch (Exception e) {
            Logger.debug(e, "[XHR] could not inject browser headers");
        }
    }

    // ---------------------------------------------------------------------
    // abort()
    // ---------------------------------------------------------------------

    public void abort() {
        Logger.info("[XHR] abort() {}", DiagnosticPaths.safeUrl(requestUrl));

        aborted.set(true);
        status = 0;
        statusText = "";
        responseText = "";

        if (readyState != UNSENT && readyState != DONE) {
            setReadyState(DONE);
            fireError();
            fireLoadEnd();
        }
    }

    // ---------------------------------------------------------------------
    // Response headers
    // ---------------------------------------------------------------------

    public String getResponseHeader(String name) {
        if (name == null) {
            return null;
        }

        for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String getAllResponseHeaders() {
        if (responseHeaders.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : responseHeaders.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // readyState + event helpers
    // ---------------------------------------------------------------------

    private void setReadyState(int state) {
        readyState = state;
        Logger.debug("[XHR] readyState={} url={}", state, DiagnosticPaths.safeUrl(requestUrl));

        EventSink sink = eventSink;
        if (sink != null) {
            try {
                sink.onReadyStateChange(state);
            } catch (Throwable t) {
                Logger.warn(t, "[XHR] EventSink.onReadyStateChange failed");
            }
        }
    }

    private void fireLoad() {
        EventSink sink = eventSink;
        if (sink != null) {
            try {
                sink.onLoad();
            } catch (Throwable t) {
                Logger.warn(t, "[XHR] EventSink.onLoad failed");
            }
        }
    }

    private void fireError() {
        EventSink sink = eventSink;
        if (sink != null) {
            try {
                sink.onError();
            } catch (Throwable t) {
                Logger.warn(t, "[XHR] EventSink.onError failed");
            }
        }
    }

    private void fireLoadEnd() {
        EventSink sink = eventSink;
        if (sink != null) {
            try {
                sink.onLoadEnd();
            } catch (Throwable t) {
                Logger.warn(t, "[XHR] EventSink.onLoadEnd failed");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String resolveUrl(String url) {
        if (url == null || url.isBlank()) {
            return baseUrl != null ? baseUrl : "";
        }
        try {
            return URI.create(baseUrl != null ? baseUrl : "https://example.com/")
                    .resolve(url)
                    .toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static String findHeader(
            Map<String, String> headers,
            String wanted
    ) {
        if (headers == null || wanted == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Properties
    // ---------------------------------------------------------------------

    public int getReadyState() {
        return readyState;
    }

    public int getStatus() {
        return status;
    }

    public String getStatusText() {
        return statusText;
    }

    public String getResponseText() {
        return responseText;
    }

    public String getResponseURL() {
        return responseUrl;
    }

    public boolean getWithCredentials() {
        return withCredentials;
    }

    public void setWithCredentials(boolean withCredentials) {
        this.withCredentials = withCredentials;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = Math.max(0, timeout);
    }

    public boolean isAsync() {
        return async;
    }

    public String getMethod() {
        return method;
    }

    public String getRequestUrl() {
        return requestUrl;
    }
}
