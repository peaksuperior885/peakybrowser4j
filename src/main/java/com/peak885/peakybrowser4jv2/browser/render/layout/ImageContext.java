package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import org.jsoup.nodes.Element;
import org.tinylog.Logger;

import java.io.IOException;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps image requests alive while a page's layout tree is rebuilt. */
final class ImageContext {
    private static final ExecutorService IMAGE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "PeakyBrowser-Image");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Element, ImageContext> CONTEXTS = new WeakHashMap<>();

    private final BrowserLoader loader;
    private final WeakReference<Element> sourceElement;
    private String url;
    private ImageData image;
    private boolean loading;
    private boolean failed;

    private ImageContext(Element element, BrowserLoader loader) {
        this.loader = loader;
        this.sourceElement = new WeakReference<>(element);
    }

    static synchronized ImageContext forElement(Element element, BrowserLoader loader) {
        return CONTEXTS.computeIfAbsent(element, ignored -> new ImageContext(element, loader));
    }

    synchronized ImageData image() {
        return image;
    }

    synchronized boolean failed() {
        return failed;
    }

    synchronized void load(String requestedUrl, Runnable invalidator) {
        if (requestedUrl == null || requestedUrl.isBlank()) {
            failed = true;
            return;
        }
        if (requestedUrl.equals(url) && (loading || image != null || failed)) {
            return;
        }
        url = requestedUrl;
        final String source = describeSource(sourceElement.get());
        final String imageSummary = summarize(requestedUrl);
        image = null;
        failed = false;
        loading = true;
        IMAGE_EXECUTOR.execute(() -> {
            ImageData loaded = null;
            try {
                loaded = loader.loadImage(requestedUrl);
            } catch (IOException e) {
                Logger.warn(e, "[IMAGE-SOURCE] Failed image from {} ({})", source, imageSummary);
            }
            synchronized (ImageContext.this) {
                if (!requestedUrl.equals(url)) {
                    return;
                }
                image = loaded;
                failed = loaded == null;
                loading = false;
                Logger.info("[IMAGE-SOURCE] Loaded image from {} ({}, success: {})", source, imageSummary, !failed);
            }
            if (invalidator != null) {
                invalidator.run();
            }
        });
    }

    private static String describeSource(Element element) {
        if (element == null) return "detached element";
        StringBuilder description = new StringBuilder(element.tagName());
        String id = element.id();
        String classes = element.className();
        if (!id.isBlank()) description.append('#').append(id);
        if (!classes.isBlank()) description.append('.').append(classes.trim().replaceAll("\\s+", "."));
        return description.toString();
    }

    private static String summarize(String value) {
        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = value.indexOf(',');
            String metadata = comma < 0 ? value : value.substring(0, comma);
            return metadata + ",<redacted> (chars=" + value.length() + ")";
        }
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(value);
        return parsed == null ? "non-http URL" : parsed.scheme() + "://" + parsed.host() + parsed.encodedPath();
    }
}
