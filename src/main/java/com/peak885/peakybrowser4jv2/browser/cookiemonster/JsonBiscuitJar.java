package com.peak885.peakybrowser4jv2.browser.cookiemonster;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.tinylog.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class JsonBiscuitJar implements BiscuitJar {

    private final File cookieFile;
    private final Gson gson;
    private final List<StoredCookie> cookies;

    public JsonBiscuitJar(File cacheDirectory) {
        if (cacheDirectory != null && !cacheDirectory.exists()) {
            cacheDirectory.mkdirs();
        }
        this.cookieFile = new File(cacheDirectory, "cookies.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.cookies = loadFromFile();

        Logger.info("[Cookies] JSON cookie store initialized: {}", cookieFile.getAbsolutePath());
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        removeExpired();

        List<Cookie> result = new ArrayList<>();

        // Purge cookies that can't legally appear in a Cookie header (e.g. an SG_SS
        // value with CR/LF persisted by an earlier build) - OkHttp throws on them and
        // that would fail every request to the site.
        if (cookies.removeIf(sc -> !isHeaderSafe(sc.name) || !isHeaderSafe(sc.value))) {
            saveToFile();
        }

        for (StoredCookie sc : cookies) {
            Cookie cookie = sc.toOkCookie();

            if (cookie.matches(url)) {
                result.add(cookie);
            }
        }

        return result;
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> incomingCookies) {
        if (incomingCookies == null || incomingCookies.isEmpty()) {
            return;
        }

        for (Cookie incoming : incomingCookies) {
            if (!isHeaderSafe(incoming.name()) || !isHeaderSafe(incoming.value())) {
                Logger.warn("[Cookies] dropping cookie '{}' with characters that can't be sent in a header", incoming.name());
                continue;
            }
            // Remove if expired or max-age=0
            if (incoming.expiresAt() <= System.currentTimeMillis()) {
                removeCookie(incoming);
                continue;
            }

            // Upsert logic: remove existing match if present, then add new
            cookies.removeIf(sc ->
                    sc.name.equals(incoming.name()) &&
                            sc.domain.equals(incoming.domain()) &&
                            sc.path.equals(incoming.path())
            );

            cookies.add(StoredCookie.fromOkCookie(incoming));
        }

        saveToFile();
    }

    private static boolean isHeaderSafe(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                return false;
            }
        }
        return true;
    }

    private synchronized void removeCookie(Cookie target) {
        cookies.removeIf(sc ->
                sc.name.equals(target.name()) &&
                        sc.domain.equals(target.domain()) &&
                        sc.path.equals(target.path())
        );
    }

    private synchronized void removeExpired() {
        long now = System.currentTimeMillis();
        boolean removed = cookies.removeIf(sc -> sc.expiresAt <= now);
        if (removed) {
            saveToFile();
        }
    }

    private synchronized List<StoredCookie> loadFromFile() {
        if (!cookieFile.exists()) {
            return new ArrayList<>();
        }
        try (FileReader reader = new FileReader(cookieFile)) {
            Type listType = new TypeToken<ArrayList<StoredCookie>>(){}.getType();
            List<StoredCookie> loaded = gson.fromJson(reader, listType);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (IOException e) {
            Logger.error(e, "[Cookies] Failed to load cookies from JSON file");
            return new ArrayList<>();
        }
    }

    private synchronized void saveToFile() {
        try (FileWriter writer = new FileWriter(cookieFile)) {
            gson.toJson(cookies, writer);
        } catch (IOException e) {
            Logger.error(e, "[Cookies] Failed to save cookies to JSON file");
        }
    }

    // Helper POJO container for Gson serialization
    private static class StoredCookie {
        String name;
        String value;
        String domain;
        String path;
        long expiresAt;
        boolean secure;
        boolean httpOnly;
        boolean hostOnly;

        static StoredCookie fromOkCookie(Cookie c) {
            StoredCookie sc = new StoredCookie();
            sc.name = c.name();
            sc.value = c.value();
            sc.domain = c.domain();
            sc.path = c.path();
            sc.expiresAt = c.expiresAt();
            sc.secure = c.secure();
            sc.httpOnly = c.httpOnly();
            sc.hostOnly = c.hostOnly();
            return sc;
        }

        Cookie toOkCookie() {
            Cookie.Builder builder = new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path);

            if (hostOnly) {
                builder.hostOnlyDomain(domain);
            } else {
                builder.domain(domain);
            }

            if (expiresAt != Long.MAX_VALUE) {
                builder.expiresAt(expiresAt);
            }

            if (secure) builder.secure();
            if (httpOnly) builder.httpOnly();

            return builder.build();
        }
    }
}