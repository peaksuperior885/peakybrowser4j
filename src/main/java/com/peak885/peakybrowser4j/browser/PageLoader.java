package com.peak885.peakybrowser4j.browser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

public class PageLoader {
    private String currentPageUrl;
    private final OkHttpClient client = new OkHttpClient();

    public String load(String url) {
        currentPageUrl = url;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new Exception("Unexpected code " + response);

                // Consume the stream ONCE
                return response.body().string();
            }
        } catch (Exception e) {
            Logger.error("Failed to load: {}", url, e);
            return "<html><body><h1>Load failed</h1></body></html>";
        }
    }

    public byte[] downloadImageBytes(String url) {
        try {
            String absoluteUrl = resolveUrl(url);

            if (absoluteUrl == null ||
                    !(absoluteUrl.startsWith("http://") || absoluteUrl.startsWith("https://"))) {
                Logger.error("Invalid image URL (no scheme): {}", url);
                return null;
            }

            Request request = new Request.Builder()
                    .url(absoluteUrl)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                return response.body().bytes();
            }

        } catch (Exception e) {
            Logger.error("Failed to download image: {}", url, e);
            return null;
        }
    }

    /**
     * Download CSS from any URL (with or without .css extension)
     * Verifies Content-Type and falls back if needed
     */
    public String downloadCss(String url) {
        String absoluteUrl = resolveUrl(url);

        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            Logger.warn("CSS URL is blank or null: {}", url);
            return "";
        }

        if (!(absoluteUrl.startsWith("http://") || absoluteUrl.startsWith("https://"))) {
            Logger.error("Invalid CSS URL (no scheme): {}", url);
            return "";
        }

        Logger.info("Downloading CSS from: {}", absoluteUrl);

        try {
            Request request = new Request.Builder()
                    .url(absoluteUrl)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Logger.warn("CSS download failed: {} returned HTTP {}", absoluteUrl, response.code());
                    return "";
                }

                // Verify Content-Type (helpful but not blocking)
                String contentType = response.header("Content-Type");
                if (contentType != null && !isCssContentType(contentType)) {
                    Logger.warn("Warning: {} has Content-Type: {} (expected text/css)", absoluteUrl, contentType);
                    Logger.info("Attempting to parse anyway...");
                }

                String css = response.body().string();
                Logger.info("Downloaded {} bytes of CSS from: {}", css.length(), absoluteUrl);
                return css;
            }

        } catch (Exception e) {
            Logger.error("Exception downloading CSS from {}: {}", absoluteUrl, e.getMessage());
            return "";
        }
    }

    /**
     * Download CSS with fallback
     * Tries the URL as-is, then with .css appended if that fails
     */
    public String downloadCssWithFallback(String url) {
        // Try original URL
        String css = downloadCss(url);
        if (!css.isEmpty()) {
            return css;
        }

        // If failed and URL doesn't end in .css, try appending it
        if (!url.toLowerCase().endsWith(".css")) {
            Logger.info("Original CSS URL failed, trying with .css extension: {}", url + ".css");
            css = downloadCss(url + ".css");
        }

        return css;
    }

    /**
     * Check if Content-Type indicates CSS
     */
    private boolean isCssContentType(String contentType) {
        String lowerType = contentType.toLowerCase();
        return lowerType.contains("text/css") || lowerType.contains("application/css");
    }

    public String resolveUrl(String url) {
        if (url == null || url.isBlank()) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;

        try {
            String baseUrl = currentPageUrl;
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }

            java.net.URI base = java.net.URI.create(baseUrl);
            return base.resolve(url).toString();
        } catch (Exception e) {
            Logger.error("Failed to resolve URL {}: {}", url, e.getMessage());
            return url;
        }
    }

    /**
     * Get the OkHttpClient (for use by other components)
     */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * Get the current page URL
     */
    public String getCurrentPageUrl() {
        return currentPageUrl;
    }
}