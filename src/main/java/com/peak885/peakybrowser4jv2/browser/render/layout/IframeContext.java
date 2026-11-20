package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.js.JsBridge;
import com.peak885.peakybrowser4jv2.browser.render.style.StyleResolver;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.tinylog.Logger;

import java.awt.Graphics2D;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class IframeContext {

    private static final ExecutorService IFRAME_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "PeakyBrowser-Iframe");
                thread.setDaemon(true);
                return thread;
            });

    private static final ScheduledExecutorService IFRAME_REFLOW_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "PeakyBrowser-Iframe-Reflow");
                thread.setDaemon(true);
                return thread;
            });

    private enum LoadState {
        NOT_STARTED,
        LOADING,
        LOADED,
        FAILED
    }

    private final Element iframeElement;
    private final BrowserLoader browserLoader;
    private final Runnable globalRepaintTrigger;

    private JsBridge childJsBridge;
    private Box childLayoutTreeRoot;
    private Document childDocument;
    private float childFrameWidth;
    private ScheduledFuture<?> pendingReflow;

    private LoadState loadState = LoadState.NOT_STARTED;
    private String currentUrl;

    public IframeContext(
            Element iframeElement,
            BrowserLoader loader,
            Runnable repaintTrigger
    ) {
        this.iframeElement = iframeElement;
        this.browserLoader = loader;
        this.globalRepaintTrigger =
                repaintTrigger != null ? repaintTrigger : () -> {};
    }

    public synchronized void loadAsync(float frameWidth) {
        String targetUrl = resolveUrl();

        if (targetUrl == null) {
            return;
        }

        // Don't repeatedly reload the same document every layout pass.
        if (targetUrl.equals(currentUrl)
                && (loadState == LoadState.LOADING
                || loadState == LoadState.LOADED)) {
            return;
        }

        currentUrl = targetUrl;
        loadState = LoadState.LOADING;

        IFRAME_EXECUTOR.execute(() -> loadDocument(targetUrl, frameWidth));
    }

    private String resolveUrl() {
        String src = iframeElement.absUrl("src");

        if (src == null || src.isBlank()) {
            src = iframeElement.attr("src").trim();
        }

        if (src.isBlank() || "about:blank".equalsIgnoreCase(src)) {
            return null;
        }

        return src;
    }

    /**
     * Build the request a browser sends for an iframe navigation. In particular it carries the
     * embedding page as Referer: reCAPTCHA's /fallback frame answers HTTP 400 without one, and
     * the anchor frame uses it to validate the embedding site.
     */
    private okhttp3.Request buildFrameRequest(String targetUrl) {
        okhttp3.HttpUrl target = okhttp3.HttpUrl.parse(targetUrl);
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(targetUrl)
                .header("User-Agent", com.peak885.peakybrowser4jv2.browser.http.HttpManager.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "iframe")
                .header("Sec-Fetch-Mode", "navigate");

        okhttp3.HttpUrl parent = okhttp3.HttpUrl.parse(iframeElement.baseUri());
        if (parent != null && target != null) {
            boolean sameOrigin = parent.scheme().equals(target.scheme())
                    && parent.host().equals(target.host())
                    && parent.port() == target.port();
            boolean sameSite = parent.topPrivateDomain() != null
                    && parent.topPrivateDomain().equals(target.topPrivateDomain());

            builder.header("Sec-Fetch-Site",
                    sameOrigin ? "same-origin" : sameSite ? "same-site" : "cross-site");

            // Default browser policy (strict-origin-when-cross-origin): full URL for same-origin,
            // origin only otherwise.
            String referer = sameOrigin
                    ? parent.newBuilder().fragment(null).build().toString()
                    : parent.scheme() + "://" + parent.host()
                    + (parent.port() == okhttp3.HttpUrl.defaultPort(parent.scheme()) ? "" : ":" + parent.port())
                    + "/";
            builder.header("Referer", referer);
        }
        return builder.build();
    }

    private void loadDocument(String targetUrl, float frameWidth) {
        try {
            Logger.info(
                    "[IFRAME] Loading child document: {}",
                    targetUrl
            );

            boolean webUrl = okhttp3.HttpUrl.parse(targetUrl) != null;
            var response = webUrl
                    ? browserLoader.getHttpManager().execute(buildFrameRequest(targetUrl))
                    : browserLoader.getHttpManager().get(targetUrl); // data:, file:, ...

            String html = response.getBodyAsString();

            if (html == null) {
                throw new IllegalStateException(
                        "Iframe response contained no body"
                );
            }

            int dataImageCount = 0;
            java.util.regex.Matcher dataImages = java.util.regex.Pattern
                    .compile("(?i)data:image/[^,\\s\\\"']+;base64,")
                    .matcher(html);
            while (dataImages.find()) dataImageCount++;
            if (dataImageCount > 0) {
                Logger.info("[RECAPTCHA-HTML-DIAG] iframe HTML had {} base64 image URI(s); malformed-PNG-marker-present={}",
                        dataImageCount, html.contains("iVBORw0KGgBX"));
            }

            Document childDocument =
                    Jsoup.parse(html, targetUrl);

            if (targetUrl.contains("/recaptcha/enterprise/fallback")
                    || targetUrl.contains("/recaptcha/api2/fallback")) {
                String statusText = childDocument.select(
                        ".fbc-alert, .fbc-main-message, .fbc-message, [role=alert]"
                ).text().replaceAll("\\s+", " ").trim();
                if (statusText.length() > 500) {
                    statusText = statusText.substring(0, 500) + "… [truncated]";
                }
                Logger.warn("[RECAPTCHA-FALLBACK] server fallback page status text: {}",
                        statusText.isBlank() ? "<none in known alert/message elements>" : statusText);
            }

            JsBridge newJsBridge = new JsBridge(
                    childDocument,
                    browserLoader.getHttpManager(),
                    this::scheduleChildReflow
            );

            StyleResolver resolver = new StyleResolver();

            LayoutEngine layoutEngine =
                    new LayoutEngine(resolver, browserLoader);

            layoutEngine.setPipelineInvalidator(
                    this::scheduleChildReflow
            );

            Box newRoot =
                    layoutEngine.build(childDocument, frameWidth);

            synchronized (this) {
                childJsBridge = newJsBridge;
                childLayoutTreeRoot = newRoot;
                this.childDocument = childDocument;
                this.childFrameWidth = frameWidth;
                loadState = LoadState.LOADED;
            }

            IFRAME_EXECUTOR.execute(() -> {
                try {
                    newJsBridge.executeInlineScripts();
                } catch (Exception e) {
                    Logger.warn(
                            e,
                            "[IFRAME] JavaScript execution failed: {}",
                            targetUrl
                    );
                }

                // Scripts can append challenge images and other replaced elements
                // after the initial box tree was created.
                scheduleChildReflow();
                globalRepaintTrigger.run();
            });

            globalRepaintTrigger.run();

        } catch (Exception e) {
            synchronized (this) {
                loadState = LoadState.FAILED;
            }

            Logger.warn(
                    e,
                    "[IFRAME] Failed to load: {}",
                    targetUrl
            );

            globalRepaintTrigger.run();
        }
    }

    /** Coalesce DOM writes inside an iframe into a fresh custom-renderer layout. */
    private synchronized void scheduleChildReflow() {
        if (loadState != LoadState.LOADED || childDocument == null) {
            return;
        }
        if (pendingReflow != null) {
            pendingReflow.cancel(false);
        }
        pendingReflow = IFRAME_REFLOW_EXECUTOR.schedule(
                this::rebuildChildLayout,
                60,
                TimeUnit.MILLISECONDS
        );
    }

    private void rebuildChildLayout() {
        Document document;
        float frameWidth;
        synchronized (this) {
            pendingReflow = null;
            if (loadState != LoadState.LOADED || childDocument == null) {
                return;
            }
            document = childDocument;
            frameWidth = childFrameWidth;
        }

        try {
            LayoutEngine layoutEngine = new LayoutEngine(new StyleResolver(), browserLoader);
            layoutEngine.setPipelineInvalidator(this::scheduleChildReflow);
            Box refreshedRoot = layoutEngine.build(document, frameWidth);
            synchronized (this) {
                if (loadState == LoadState.LOADED && childDocument == document) {
                    childLayoutTreeRoot = refreshedRoot;
                }
            }
            globalRepaintTrigger.run();
        } catch (RuntimeException e) {
            Logger.warn(e, "[IFRAME] Failed to refresh child layout: {}", currentUrl);
        }
    }

    public synchronized void computeSubLayout(
            float frameWidth,
            float startX,
            float startY
    ) {
        if (childLayoutTreeRoot == null) {
            return;
        }

        childLayoutTreeRoot.layout(
                frameWidth,
                startX,
                startY
        );
    }

    public synchronized void paintSubTree(Graphics2D graphics) {
        if (childLayoutTreeRoot == null) {
            return;
        }

        childLayoutTreeRoot.paint(graphics);
    }

    public synchronized void terminate() {
        if (pendingReflow != null) {
            pendingReflow.cancel(false);
            pendingReflow = null;
        }
        if (childJsBridge != null) {
            try {
                childJsBridge.close();
            } catch (Exception e) {
                Logger.warn(
                        e,
                        "[IFRAME] Failed to close child JS bridge"
                );
            }

            childJsBridge = null;
        }

        childLayoutTreeRoot = null;
        childDocument = null;
        loadState = LoadState.NOT_STARTED;
        currentUrl = null;
    }

    public synchronized boolean isLoaded() {
        return loadState == LoadState.LOADED;
    }

    public synchronized boolean isLoading() {
        return loadState == LoadState.LOADING;
    }
}
