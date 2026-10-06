package com.peak885.peakybrowser4jv2.browser.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.peak885.peakybrowser4jv2.browser.cookiemonster.BiscuitJarAdapter;
import com.peak885.peakybrowser4jv2.browser.cookiemonster.JsonBiscuitJar;
import okhttp3.*;

import java.net.MalformedURLException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class HttpManager {

    /**
     * Compatibility UA: Chromium capability tokens + PeakyBrowser identity
     * (same pattern as Edg/, OPR/, etc.). Sites that gate on known engines
     * still get a full document; our brand remains visible at the end.
     */
    public static final String USER_AGENT = UserAgent.build();

    private static final long DISK_CACHE_SIZE =
            50L * 1024L * 1024L; // 50 MiB

    /*
     * Caffeine is only the tiny "hot" cache.
     *
     * This is deliberately small because HttpResponse contains the
     * complete response body as a byte array.
     */
    private static final long MEMORY_CACHE_MAX_WEIGHT =
            8L * 1024L * 1024L; // 8 MiB

    /*
     * Never put an individual resource larger than this into the
     * Caffeine cache.
     *
     * Large images, videos, downloads, etc. should remain in OkHttp's
     * disk cache instead of occupying JVM heap memory.
     */
    private static final int MAX_MEMORY_ENTRY_SIZE =
            1 * 1024 * 1024; // 1 MiB

    private static final long MEMORY_CACHE_TTL_MINUTES = 5;

    private final OkHttpClient client;

    private final Cache<String, HttpResponse> memoryCache;

    private final JsonBiscuitJar biscuitJar;

    public HttpManager() {

        File cacheDirectory = getCacheDirectory();

        /*
         * Store cookies next to the browser's persistent cache rather
         * than inside the HTTP cache itself.
         *
         * HttpManager does not understand cookie semantics. It simply
         * gives the cookie jar to OkHttp.
         */
        try {

            File parent = cacheDirectory.getParentFile();

            if (parent == null) {
                parent = cacheDirectory;
            }

            Files.createDirectories(parent.toPath());

            File cookieDatabase = new File(
                    parent,
                    "cookies.sqlite"
            );

            this.biscuitJar = new JsonBiscuitJar(cookieDatabase);

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Failed to initialize cookie database",
                    e
            );
        }

        /*
         * Caffeine hot cache.
         *
         * maximumWeight() makes the cache roughly size-aware instead
         * of simply limiting it to a number of entries.
         *
         * The weight of each entry is its response-body size.
         */
        this.memoryCache = Caffeine.<String, HttpResponse>newBuilder()
                .initialCapacity(64)
                .maximumWeight(MEMORY_CACHE_MAX_WEIGHT)
                .weigher((String key, HttpResponse response) ->
                        response.getBodySize())
                .expireAfterWrite(
                        MEMORY_CACHE_TTL_MINUTES,
                        TimeUnit.MINUTES
                )
                .recordStats()
                .build();

        /*
         * OkHttp handles the actual HTTP cache.
         *
         * BiscuitJar handles persistent browser cookies.
         */
        this.client = new OkHttpClient.Builder()
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)

                .connectTimeout(
                        Duration.ofSeconds(10)
                )

                .readTimeout(
                        Duration.ofSeconds(15)
                )

                .cookieJar(
                        new BiscuitJarAdapter(biscuitJar)
                )

                .cache(
                        new okhttp3.Cache(
                                cacheDirectory,
                                DISK_CACHE_SIZE
                        )
                )

                .build();
    }

    /**
     * Returns the persistent browser cache directory.
     */
    @NotNull
    private static File getCacheDirectory() {

        String os = System
                .getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);

        File directory;

        /*
         * Windows:
         *
         * %LOCALAPPDATA%\PeakBrowser\cache
         */
        if (os.contains("win")) {

            String localAppData =
                    System.getenv("LOCALAPPDATA");

            if (localAppData != null
                    && !localAppData.isBlank()) {

                directory = new File(
                        localAppData,
                        "PeakBrowser/cache"
                );

            } else {

                directory = new File(
                        System.getProperty("user.home"),
                        "AppData/Local/PeakBrowser/cache"
                );
            }

            /*
             * macOS:
             *
             * ~/Library/Caches/PeakBrowser
             */
        } else if (os.contains("mac")) {

            directory = new File(
                    System.getProperty("user.home"),
                    "Library/Caches/PeakBrowser"
            );

            /*
             * Linux / Unix:
             *
             * Prefer XDG_CACHE_HOME.
             */
        } else {

            String xdg =
                    System.getenv("XDG_CACHE_HOME");

            if (xdg != null && !xdg.isBlank()) {

                directory = new File(
                        xdg,
                        "PeakBrowser"
                );

            } else {

                directory = new File(
                        System.getProperty("user.home"),
                        ".cache/PeakBrowser"
                );
            }
        }

        try {

            Files.createDirectories(
                    directory.toPath()
            );

        } catch (IOException e) {

            /*
             * The cache should never prevent PeakBrowser from
             * starting.
             */
            Logger.warn(
                    e,
                    "[HTTP] Failed to create persistent cache directory: {}",
                    directory
            );

            directory = new File(
                    System.getProperty("java.io.tmpdir"),
                    "peakybrowser4j-cache"
            );

            try {

                Files.createDirectories(
                        directory.toPath()
                );

            } catch (IOException fallbackException) {

                Logger.warn(
                        fallbackException,
                        "[HTTP] Failed to create fallback cache directory: {}",
                        directory
                );
            }
        }

        Logger.info(
                "[HTTP] Disk cache directory: {}",
                directory.getAbsolutePath()
        );

        return directory;
    }

    @NotNull
    public HttpResponse get(
            @NotNull String url
    ) throws IOException {

        return get(url, "*/*");
    }

    @NotNull
    public HttpResponse get(
            @NotNull String url,
            @NotNull String accept
    ) throws IOException {

        if (url.trim().toLowerCase(Locale.ROOT).startsWith("data:")) {
            try {
                int commaIndex = url.indexOf(',');
                if (commaIndex < 0) {
                    throw new MalformedURLException("Invalid data URI formatting: missing comma separator");
                }

                String metadata = url.substring(5, commaIndex).toLowerCase(Locale.ROOT); // skip "data:"
                String rawDataPart = url.substring(commaIndex + 1);

                // 1. FIRST FIX: Always URL-decode the string data layer first to strip out %2F, %3D, etc.
                String sanitizedDataPart = java.net.URLDecoder.decode(rawDataPart, StandardCharsets.UTF_8);

                String contentType = "text/plain";
                boolean isBase64 = metadata.contains(";base64");

                // Isolate the clean Content-Type segment
                if (!metadata.isBlank()) {
                    String[] parts = metadata.split(";");
                    if (parts.length > 0 && !parts[0].contains("=")) {
                        contentType = parts[0]; // e.g. "image/png" or "text/css"
                    }
                }

                // 2. Extract the decoded byte arrays safely
                byte[] decodedBody;
                if (isBase64) {
                    // Strip any remaining inline whitespace format marks
                    String cleanBase64 = sanitizedDataPart.replaceAll("\\s", "");
                    decodedBody = java.util.Base64.getDecoder().decode(cleanBase64);
                } else {
                    decodedBody = sanitizedDataPart.getBytes(StandardCharsets.UTF_8);
                }

                Logger.info("[HTTP] Intercepted data URI. Type: {}, Size: {} bytes", contentType, decodedBody.length);

                Map<String, String> syntheticHeaders = new LinkedHashMap<>();
                syntheticHeaders.put("content-type", contentType);
                syntheticHeaders.put("content-length", String.valueOf(decodedBody.length));

                return new HttpResponse(
                        200,
                        "OK",
                        url,
                        true,
                        contentType,
                        syntheticHeaders,
                        decodedBody
                );

            } catch (Exception e) {
                Logger.error(e, "[HTTP] Failed parsing data URI stream shortcut logic");
                throw new IOException("Failed to decode embedded data URI asset", e);
            }
        }


        /*
         * This is only the Caffeine key.
         *
         * OkHttp's disk cache remains responsible for proper HTTP
         * cache validation.
         */
        String cacheKey =
                "GET:" + url + "|Accept:" + accept;

        /*
         * Layer 1:
         *
         * Tiny in-memory hot cache.
         */
        HttpResponse cachedResponse =
                memoryCache.getIfPresent(cacheKey);

        if (cachedResponse != null) {

            Logger.info(
                    "[HTTP] Memory cache HIT: {}",
                    url
            );

            return cachedResponse;
        }

        Logger.debug(
                "[HTTP] Memory cache MISS: {}",
                url
        );

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.9")
                // NOTE: no manual Accept-Encoding here. Setting it ourselves makes OkHttp
                // skip its transparent gzip decoding (and it can't decode br/deflate), so
                // scripts/CSS arrived as compressed garbage. Without it OkHttp sends
                // "gzip" and decodes the body for us.
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .build();
        /*
         * Layer 2:
         *
         * OkHttp now gets to perform its normal HTTP cache logic.
         *
         * This may result in:
         *
         *   memory miss
         *       ↓
         *   disk cache HIT
         *
         * or:
         *
         *   memory miss
         *       ↓
         *   stale disk entry
         *       ↓
         *   conditional network request
         *
         * or:
         *
         *   memory miss
         *       ↓
         *   network
         */
        HttpResponse response =
                execute(request);

        /*
         * Only promote small, successful and reasonably cacheable
         * resources into the Caffeine hot cache.
         */
        if (response.isSuccessful()
                && isMemoryCacheable(response)
                && response.getBodySize()
                <= MAX_MEMORY_ENTRY_SIZE) {

            memoryCache.put(
                    cacheKey,
                    response
            );

            Logger.debug(
                    "[HTTP] Stored in memory cache: {} ({} bytes)",
                    url,
                    response.getBodySize()
            );
        }

        return response;
    }

    /**
     * Executes an arbitrary OkHttp request.
     *
     * The returned HttpResponse is detached from OkHttp's Response
     * object and can safely outlive it.
     */
    @NotNull
    public HttpResponse execute(
            @NotNull Request request
    ) throws IOException {

        Logger.info(
                "[HTTP] {} {}",
                request.method(),
                request.url()
        );

        for (String name : request.headers().names()) {

            Logger.debug(
                    "[HTTP] Request header: {}: {}",
                    name,
                    request.header(name)
            );
        }

        try (Response response =
                     client.newCall(request).execute()) {

            Logger.info(
                    "[HTTP] Response: {} {}",
                    response.code(),
                    response.message()
            );

            Logger.info(
                    "[HTTP] Final URL: {}",
                    response.request().url()
            );

            Logger.info(
                    "[HTTP] Content-Type: {}",
                    response.header("Content-Type")
            );

            /*
             * Useful HTTP cache diagnostics.
             */
            String cacheControl =
                    response.header("Cache-Control");

            if (cacheControl != null) {

                Logger.debug(
                        "[HTTP] Cache-Control: {}",
                        cacheControl
                );
            }

            String etag =
                    response.header("ETag");

            if (etag != null) {

                Logger.debug(
                        "[HTTP] ETag: {}",
                        etag
                );
            }

            String lastModified =
                    response.header("Last-Modified");

            if (lastModified != null) {

                Logger.debug(
                        "[HTTP] Last-Modified: {}",
                        lastModified
                );
            }

            String contentEncoding = response.header("Content-Encoding");
            Logger.info("[HTTP] Content-Encoding: {}", contentEncoding);

            String vary =
                    response.header("Vary");

            if (vary != null) {

                Logger.debug(
                        "[HTTP] Vary: {}",
                        vary
                );
            }

            /*
             * OkHttp exposes whether this response involved its
             * persistent HTTP cache.
             */
            Logger.debug(
                    "[HTTP] Cache response: {}",
                    response.cacheResponse() != null
            );

            Logger.debug(
                    "[HTTP] Network response: {}",
                    response.networkResponse() != null
            );

            ResponseBody body =
                    response.body();

            byte[] bytes =
                    body != null
                            ? body.bytes()
                            : new byte[0];

            Logger.info(
                    "[HTTP] Body size: {} bytes",
                    bytes.length
            );

            String finalUrl =
                    response.request()
                            .url()
                            .toString();

            /*
             * Normalize headers to lowercase.
             *
             * HTTP header names are case-insensitive.
             */
            Map<String, String> headers =
                    new LinkedHashMap<>();

            for (String name :
                    response.headers().names()) {

                String value =
                        response.header(name);

                if (value != null) {

                    headers.put(
                            name.toLowerCase(Locale.ROOT),
                            value
                    );
                }
            }

            return new HttpResponse(
                    response.code(),
                    response.message(),
                    finalUrl,
                    response.isSuccessful(),
                    response.header("Content-Type"),
                    headers,
                    bytes
            );
        }
    }

    /**
     * Determines whether a response is suitable for the small
     * Caffeine hot cache.
     *
     * OkHttp remains responsible for the real HTTP caching rules.
     */
    private boolean isMemoryCacheable(
            @NotNull HttpResponse response
    ) {

        /*
         * A response which sets a cookie should not be duplicated
         * into the application-level hot cache.
         *
         * The cookie jar needs to remain the authoritative state.
         */
        if (response.getHeader("set-cookie") != null) {

            return false;
        }

        String cacheControl =
                response.getHeader("cache-control");

        if (cacheControl != null) {

            CacheControl parsed =
                    CacheControl.parse(
                            Headers.of(
                                    "Cache-Control",
                                    cacheControl
                            )
                    );

            /*
             * Explicitly forbidden from caching.
             */
            if (parsed.noStore()) {

                return false;
            }

            /*
             * Keep private responses out of the generic hot cache.
             *
             * OkHttp's private disk cache remains available.
             */
            if (parsed.isPrivate()) {

                return false;
            }
        }

        /*
         * Vary: * means the response cannot be reused by a cache.
         */
        String vary =
                response.getHeader("vary");

        if (vary != null
                && vary.trim().equals("*")) {

            return false;
        }

        return true;
    }

    @NotNull
    public OkHttpClient getClient() {

        return client;
    }

    public void clearMemoryCache() {

        memoryCache.invalidateAll();

        Logger.info(
                "[HTTP] Memory cache cleared"
        );
    }

    /**
     * Clears both:
     *
     * - Caffeine hot cache
     * - OkHttp persistent HTTP cache
     *
     * Cookies are intentionally NOT cleared.
     */
    public void clearAllCaches() {

        memoryCache.invalidateAll();

        okhttp3.Cache cache =
                client.cache();

        if (cache != null) {

            try {

                cache.evictAll();

            } catch (IOException e) {

                Logger.warn(
                        e,
                        "[HTTP] Failed to clear disk cache"
                );
            }
        }

        Logger.info(
                "[HTTP] All HTTP caches cleared"
        );
    }

    /**
     * Returns Caffeine cache statistics.
     */
    @NotNull
    public String getMemoryCacheStats() {

        return memoryCache.stats().toString();
    }

    public static final class HttpResponse {

        private final int statusCode;
        private final String statusMessage;
        private final String finalUrl;
        private final boolean successful;
        private final String contentType;
        private final Map<String, String> headers;
        private final byte[] body;

        public HttpResponse(
                int statusCode,
                @NotNull String statusMessage,
                @NotNull String finalUrl,
                boolean successful,
                @Nullable String contentType,
                @NotNull Map<String, String> headers,
                @NotNull byte[] body
        ) {

            this.statusCode =
                    statusCode;

            this.statusMessage =
                    statusMessage;

            this.finalUrl =
                    finalUrl;

            this.successful =
                    successful;

            this.contentType =
                    contentType;

            this.headers =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(headers)
                    );

            /*
             * Defensive copy so the cached response cannot be
             * corrupted by callers.
             */
            this.body =
                    body.clone();
        }

        public int getStatusCode() {

            return statusCode;
        }

        @NotNull
        public String getStatusMessage() {

            return statusMessage;
        }

        @NotNull
        public String getFinalUrl() {

            return finalUrl;
        }

        public boolean isSuccessful() {

            return successful;
        }

        @Nullable
        public String getContentType() {

            return contentType;
        }

        @NotNull
        public Map<String, String> getHeaders() {

            return headers;
        }

        @NotNull
        public byte[] getBody() {

            /*
             * Defensive copy.
             */
            return body.clone();
        }

        /**
         * Returns the body size without allocating another byte array.
         *
         * This is particularly useful for Caffeine's weigher.
         */
        public int getBodySize() {

            return body.length;
        }

        @NotNull
        public String getBodyAsString() {

            return new String(
                    body,
                    StandardCharsets.UTF_8
            );
        }

        @Nullable
        public String getHeader(
                @NotNull String name
        ) {

            return headers.get(
                    name.toLowerCase(Locale.ROOT)
            );
        }
    }

    @NotNull
    public Response executeStreaming(
            @NotNull Request request
    ) throws IOException {

        Logger.info(
                "[HTTP] Streaming {} {}",
                request.method(),
                request.url()
        );

        Response response = client.newCall(request).execute();

        Logger.info(
                "[HTTP] Streaming {} {}",
                request.method(),
                request.url()
        );

        for (String name : request.headers().names()) {
            Logger.info(
                    "[HTTP] Streaming request header: {}: {}",
                    name,
                    request.header(name)
            );
        }

        Logger.info(
                "[HTTP] Streaming response: {} {}",
                response.code(),
                response.message()
        );

        Logger.info(
                "[HTTP] Final URL: {}",
                response.request().url()
        );

        Logger.info(
                "[HTTP] Content-Type: {}",
                response.header("Content-Type")
        );

        Logger.info(
                "[HTTP] Content-Length: {}",
                response.header("Content-Length")
        );

        return response;
    }
}