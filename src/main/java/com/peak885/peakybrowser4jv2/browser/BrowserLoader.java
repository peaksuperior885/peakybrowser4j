package com.peak885.peakybrowser4jv2.browser;

import com.peak885.peakybrowser4jv2.browser.download.DownloadManager;
import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.image.ImageLoader;
import com.peak885.peakybrowser4jv2.browser.input.Inputs;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.tinylog.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BrowserLoader {

    private final HttpManager http;
    private final DownloadManager downloadManager;
    private final ImageLoader imageLoader;

    private final Map<String, ImageData> imageCache =
            new ConcurrentHashMap<>();

    public BrowserLoader() {
        this.http = new HttpManager();
        this.imageLoader = new ImageLoader();
        this.downloadManager = new DownloadManager(http);
    }

    public BrowserLoader(HttpManager http) {
        this.http = http;
        this.imageLoader = new ImageLoader();
        this.downloadManager = new DownloadManager(http);
    }

    public Document load(String url) throws IOException {

        if (url != null
                && url.startsWith("file:")) {

            return loadLocalResource(url);
        }

        /*
         * Stream the response so we can inspect headers without forcing
         * a multi-megabyte binary into a byte[] just to decide it should
         * be downloaded.
         */
        Request probeRequest = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", HttpManager.USER_AGENT)
                .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        try (okhttp3.Response raw = http.executeStreaming(probeRequest)) {

            String finalUrl = raw.request().url().toString();
            String contentType = raw.header("Content-Type");
            String disposition = raw.header("Content-Disposition");

            if (isDownloadResponse(contentType, disposition)) {
                String suggestedName =
                        filenameFromContentDisposition(disposition);

                if (suggestedName == null || suggestedName.isBlank()) {
                    suggestedName = guessFileNameFromUrl(finalUrl);
                }

                Logger.info(
                        "[NAV] Non-HTML response → download: {} (Content-Type: {})",
                        finalUrl,
                        contentType
                );

                // Body is discarded; DownloadManager will stream it again.
                throw new DownloadRequiredException(
                        finalUrl,
                        suggestedName,
                        contentType
                );
            }

            okhttp3.ResponseBody body = raw.body();
            String rawHtml = body != null
                    ? body.string()
                    : "";

            dumpRawHtml(finalUrl, rawHtml);

            /*
             * HTTP error responses are still documents.
             *
             * A browser should render the body returned by the server
             * instead of treating every non-2xx status as a transport
             * failure.
             */
            return Jsoup.parse(rawHtml, finalUrl);
        }
    }

    private Document loadLocalResource(
            String url
    ) throws IOException {

        try {
            URI uri = URI.create(url);

            Path path = Path.of(uri);

            String rawHtml = Files.readString(
                    path,
                    StandardCharsets.UTF_8
            );

            Logger.info(
                    "[LOCAL] Loaded resource: {}",
                    path
            );

            return Jsoup.parse(
                    rawHtml,
                    url
            );

        } catch (Exception e) {
            throw new IOException(
                    "Failed to load local resource: " + url,
                    e
            );
        }
    }

    /**
     * Writes the raw HTML received for a navigation to a fixed debug
     * file so it can be inspected outside the running JVM (view-source
     * style), without needing dev tools or a second browser.
     *
     * Overwrites on every navigation - only the most recent page is
     * kept. Never allowed to break navigation, so any failure here is
     * logged and swallowed.
     */
    private static void dumpRawHtml(String url, String rawHtml) {
        try {
            Path debugDir = new File(
                    System.getProperty("java.io.tmpdir"),
                    "peakybrowser-debug"
            ).toPath();

            Files.createDirectories(debugDir);

            Path dumpFile = debugDir.resolve("last-page.html");

            Files.writeString(
                    dumpFile,
                    rawHtml,
                    StandardCharsets.UTF_8
            );

            Logger.info(
                    "[DEBUG] Dumped raw HTML for {} -> {}",
                    url,
                    dumpFile.toAbsolutePath()
            );

        } catch (IOException e) {
            Logger.warn(e, "[DEBUG] Failed to dump raw HTML for {}", url);
        }
    }

    /**
     * True when the response should be saved as a file instead of
     * rendered as a web page.
     */
    static boolean isDownloadResponse(
            @Nullable String contentType,
            @Nullable String disposition
    ) {
        if (disposition != null) {
            String lower = disposition.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("attachment")
                    || lower.contains("filename=")) {
                return true;
            }
        }

        if (contentType == null || contentType.isBlank()) {
            return false;
        }

        String mime = contentType.split(";")[0]
                .trim()
                .toLowerCase(java.util.Locale.ROOT);

        // Renderable document types
        if (mime.equals("text/html")
                || mime.equals("application/xhtml+xml")
                || mime.equals("application/xml")
                || mime.equals("text/xml")
                || mime.equals("image/svg+xml")
                || mime.startsWith("text/")) {
            return false;
        }

        // Navigating directly to an image still attempts a page render path
        // (or image pipeline); treat as non-download for now.
        if (mime.startsWith("image/")) {
            return false;
        }

        // Everything else (pdf, zip, octet-stream, video, media, …)
        return true;
    }

    @Nullable
    static String filenameFromContentDisposition(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        // filename*=UTF-8''encoded-name
        java.util.regex.Matcher star = java.util.regex.Pattern
                .compile("filename\\*\\s*=\\s*([^']*)''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(header);
        if (star.find()) {
            try {
                return java.net.URLDecoder.decode(star.group(2).trim(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }

        // filename="name" or filename=name
        java.util.regex.Matcher plain = java.util.regex.Pattern
                .compile("filename\\s*=\\s*\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(header);
        if (plain.find()) {
            return plain.group(1).trim();
        }

        return null;
    }

    private static String guessFileNameFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                String name = new File(path).getName();
                if (!name.isBlank()) {
                    int q = name.indexOf('?');
                    if (q > 0) {
                        name = name.substring(0, q);
                    }
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return "download";
    }

    /**
     * Thrown by {@link #load(String)} when the response is a file
     * that should be saved rather than rendered.
     */
    public static final class DownloadRequiredException extends IOException {

        private final String downloadUrl;
        private final String suggestedFileName;
        private final String contentType;

        public DownloadRequiredException(
                String downloadUrl,
                String suggestedFileName,
                @Nullable String contentType
        ) {
            super("Download required: " + downloadUrl);
            this.downloadUrl = downloadUrl;
            this.suggestedFileName = suggestedFileName;
            this.contentType = contentType;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getSuggestedFileName() {
            return suggestedFileName;
        }

        @Nullable
        public String getContentType() {
            return contentType;
        }
    }

    public Document submitForm(
            Inputs.FormSubmission submission
    ) throws IOException {

        if (submission == null) {
            throw new IOException(
                    "Form submission is null"
            );
        }

        String action = submission.action();

        if (action == null || action.isBlank()) {
            throw new IOException(
                    "Form action is empty"
            );
        }

        String method = submission.method();

        if (method == null || method.isBlank()) {
            method = "GET";
        }

        method = method.trim().toUpperCase();

        Logger.info(
                "[FORM] {} {}",
                method,
                action
        );

        /*
         * GET forms are currently handled by BrowserWindow's
         * normal navigation path.
         */
        if ("GET".equals(method)) {
            HttpManager.HttpResponse response =
                    http.get(
                            action,
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    );

            return Jsoup.parse(
                    response.getBodyAsString(),
                    response.getFinalUrl()
            );
        }

        if (!"POST".equals(method)) {
            throw new IOException(
                    "Unsupported form method: " + method
            );
        }

        String body = submission.body();

        if (body == null) {
            body = "";
        }

        MediaType contentType =
                MediaType.parse(
                        "application/x-www-form-urlencoded"
                );

        RequestBody requestBody =
                RequestBody.create(
                        body,
                        contentType
                );

        Request request =
                new Request.Builder()
                        .url(action)
                        .post(requestBody)
                        .header(
                                "User-Agent",
                                HttpManager.USER_AGENT
                        )
                        .header(
                                "Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        )
                        .header(
                                "Accept-Language",
                                "en-US,en;q=0.9"
                        )
                        .header(
                                "Content-Type",
                                "application/x-www-form-urlencoded"
                        )
                        .build();

        HttpManager.HttpResponse response =
                http.execute(request);

        Logger.info(
                "[FORM] POST response: {} {} -> {}",
                response.getStatusCode(),
                response.getStatusMessage(),
                response.getFinalUrl()
        );

        return Jsoup.parse(
                response.getBodyAsString(),
                response.getFinalUrl()
        );
    }

    public ImageData loadImage(String url)
            throws IOException {

        String imageUrl =
                url == null
                        ? ""
                        : url.trim();

        if (imageUrl.isEmpty()) {
            throw new IOException(
                    "Image URL is empty"
            );
        }

        ImageData cached =
                imageCache.get(imageUrl);

        if (cached != null) {
            return cached;
        }

        HttpManager.HttpResponse response =
                http.get(
                        imageUrl,
                        "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
                );

        if (!response.isSuccessful()) {
            throw new IOException(
                    "HTTP " +
                            response.getStatusCode() +
                            " " +
                            response.getStatusMessage()
            );
        }

        ImageData image =
                imageLoader.load(
                        new ByteArrayInputStream(
                                response.getBody()
                        ),
                        response.getContentType()
                );

        ImageData existing =
                imageCache.putIfAbsent(
                        imageUrl,
                        image
                );

        return existing == null
                ? image
                : existing;
    }

    public String loadText(String url)
            throws IOException {

        HttpManager.HttpResponse response =
                http.get(
                        url,
                        "text/plain,text/css,application/javascript,application/json,*/*;q=0.8"
                );

        if (!response.isSuccessful()) {
            throw new IOException(
                    "HTTP " +
                            response.getStatusCode() +
                            " " +
                            response.getStatusMessage()
            );
        }

        return response.getBodyAsString();
    }

    public String parseFaviconUrl(
            Document doc,
            String baseUrl
    ) {

        Element iconLink =
                doc.selectFirst(
                        "link[rel~=(?i)^(shortcut\\s+)?icon$], " +
                                "link[rel~=(?i)^apple-touch-icon$]"
                );

        if (iconLink != null) {
            String href =
                    iconLink.absUrl("href");

            if (!href.isBlank()) {
                return href;
            }
        }

        try {
            URI uri =
                    URI.create(baseUrl);

            return uri.getScheme()
                    + "://"
                    + uri.getAuthority()
                    + "/favicon.ico";

        } catch (Exception e) {
            return null;
        }
    }

    public DownloadManager getDownloadManager() {
        return downloadManager;
    }

    public HttpManager getHttpManager() {
        return http;
    }
}