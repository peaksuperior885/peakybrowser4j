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

/**
 * fetch() backend used by the Rhino bridge.
 *
 * Supports method / headers / body and injects the browser-like
 * Origin + Sec-Fetch-* headers that Google / reCAPTCHA expect.
 */
public final class JsFetch {

    private final HttpManager http;
    private final String baseUrl;

    public JsFetch(HttpManager http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    // ---------------------------------------------------------------------
    // Convenience GET (kept for internal callers)
    // ---------------------------------------------------------------------

    public JsResponse get(String url) throws IOException {
        return request("GET", url, Map.of(), null);
    }

    // ---------------------------------------------------------------------
    // General request
    // ---------------------------------------------------------------------

    public JsResponse request(
            String method,
            String url,
            Map<String, String> headers,
            String body
    ) throws IOException {

        String resolvedUrl = resolveUrl(url);

        String upperMethod = method == null
                ? "GET"
                : method.toUpperCase(Locale.ROOT);

        Logger.info("[JS fetch] >>> {} {}", upperMethod, DiagnosticPaths.safeUrl(resolvedUrl));

        Request.Builder builder = new Request.Builder().url(resolvedUrl);

        // Browser-like headers first (page headers can still override most of them)
        injectBrowserHeaders(builder, upperMethod, resolvedUrl);

        // Headers supplied by the page
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || name.isBlank() || value == null) {
                    continue;
                }
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException e) {
                    Logger.debug("[JS fetch] Ignoring invalid header {}", name);
                }
            }
        }

        // Method + body
        if ("GET".equals(upperMethod) || "HEAD".equals(upperMethod)) {
            builder.method(upperMethod, null);
        } else {
            String contentType = findHeader(headers, "Content-Type");

            MediaType mediaType;
            try {
                mediaType = contentType != null
                        ? MediaType.parse(contentType)
                        : MediaType.parse("text/plain;charset=UTF-8");
            } catch (Exception e) {
                mediaType = MediaType.parse("application/octet-stream");
            }

            // If the page didn't set Content-Type, set a sensible default
            if (contentType == null) {
                builder.header("Content-Type", mediaType.toString());
            }

            RequestBody requestBody = RequestBody.create(
                    body == null ? "" : body,
                    mediaType
            );
            builder.method(upperMethod, requestBody);
        }

        Logger.info("[JS fetch] EXECUTE {} {}", upperMethod, DiagnosticPaths.safeUrl(resolvedUrl));

        HttpManager.HttpResponse response = http.execute(builder.build());

        Logger.info(
                "[JS fetch] <<< {} {} status={} contentType={} bodyLength={}",
                upperMethod,
                resolvedUrl,
                response.getStatusCode(),
                response.getContentType(),
                response.getBodyAsString() == null ? 0 : response.getBodyAsString().length()
        );

        return new JsResponse(response);
    }

    /**
     * Adds the headers a real browser sends for fetch/XHR.
     * Critical for Google / reCAPTCHA CORS endpoints.
     */
    private void injectBrowserHeaders(
            Request.Builder builder,
            String method,
            String requestUrl
    ) {
        try {
            URI docUri = URI.create(baseUrl != null ? baseUrl : "https://www.google.com/");
            URI reqUri = URI.create(requestUrl);

            String origin = docUri.getScheme() + "://" + docUri.getAuthority();

            boolean sameOrigin = docUri.getScheme().equalsIgnoreCase(reqUri.getScheme())
                    && docUri.getAuthority().equalsIgnoreCase(reqUri.getAuthority());

            // Origin is sent for non-GET/HEAD or cross-origin
            if (!"GET".equals(method) && !"HEAD".equals(method) || !sameOrigin) {
                builder.header("Origin", origin);
            }

            if (baseUrl != null && !baseUrl.isBlank()) {
                builder.header("Referer", baseUrl);
            }

            builder.header("Sec-Fetch-Dest", "empty");
            builder.header("Sec-Fetch-Mode", "cors");
            builder.header("Sec-Fetch-Site", sameOrigin ? "same-origin" : "cross-site");

            // Defaults only if the page didn't supply them
            builder.header("Accept", "*/*");
            builder.header("Accept-Language", "en-US,en;q=0.9");

        } catch (Exception e) {
            Logger.debug(e, "[JS fetch] could not inject browser headers");
        }
    }

    // ---------------------------------------------------------------------
    // URL resolution
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
            Logger.warn(e, "[JS fetch] resolveUrl failed for {}", DiagnosticPaths.safeUrl(url));
            return url;
        }
    }

    private static String findHeader(Map<String, String> headers, String wanted) {
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
    // JS Response
    // ---------------------------------------------------------------------

    public static final class JsResponse {

        private final HttpManager.HttpResponse response;

        public JsResponse(HttpManager.HttpResponse response) {
            this.response = response;
        }

        public int getStatus() {
            return response.getStatusCode();
        }

        public String getStatusText() {
            return response.getStatusMessage() == null
                    ? ""
                    : response.getStatusMessage();
        }

        public boolean getOk() {
            return response.isSuccessful();
        }

        public String getUrl() {
            String url = response.getFinalUrl();
            return url == null ? "" : url;
        }

        public String getText() {
            String body = response.getBodyAsString();
            return body == null ? "" : body;
        }

        public String getContentType() {
            String contentType = response.getContentType();
            return contentType == null ? "" : contentType;
        }

        public Map<String, String> getHeaders() {
            Map<String, String> original = response.getHeaders();
            if (original == null || original.isEmpty()) {
                return Map.of();
            }
            return new LinkedHashMap<>(original);
        }

        public HttpManager.HttpResponse getNativeResponse() {
            return response;
        }
    }
}
