package com.peak885.peakybrowser4jv2.browser.js;

import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import org.tinylog.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

public final class JsFetch {

    private final HttpManager http;
    private final String baseUrl;

    public JsFetch(
            HttpManager http,
            String baseUrl
    ) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public JsResponse get(String url) throws IOException {
        Logger.info("[JS fetch] GET {}", url);

        try {
            String resolvedUrl = resolveUrl(url);
            HttpManager.HttpResponse response = http.get(resolvedUrl);

            Logger.info(
                    "[JS fetch] Response: {} {} ({})",
                    response.getStatusCode(),
                    response.getStatusMessage(),
                    response.getContentType()
            );

            Logger.debug(
                    "[JS fetch] Final URL: {}",
                    response.getFinalUrl()
            );

            return new JsResponse(response);

        } catch (IOException e) {
            Logger.error(
                    e,
                    "[JS fetch] FAILED: {}",
                    url
            );

            throw e;
        }
    }

    private String resolveUrl(String url) {

        if (url == null || url.isBlank()) {
            return baseUrl;
        }

        try {
            return URI.create(baseUrl)
                    .resolve(url)
                    .toString();

        } catch (Exception e) {
            return url;
        }
    }

    public static final class JsResponse {

        private final HttpManager.HttpResponse response;

        public JsResponse(
                HttpManager.HttpResponse response
        ) {
            this.response = response;
        }

        public int getStatus() {
            return response.getStatusCode();
        }

        public String getStatusText() {
            return response.getStatusMessage();
        }

        public boolean getOk() {
            return response.isSuccessful();
        }

        public String getUrl() {
            return response.getFinalUrl();
        }

        public String getText() {
            return response.getBodyAsString();
        }

        public String getContentType() {
            return response.getContentType();
        }

        public Map<String, String> getHeaders() {
            return response.getHeaders();
        }
    }
}