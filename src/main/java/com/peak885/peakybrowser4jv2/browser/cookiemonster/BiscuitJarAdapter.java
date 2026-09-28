package com.peak885.peakybrowser4jv2.browser.cookiemonster;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.List;

public final class BiscuitJarAdapter implements CookieJar {

    private final BiscuitJar delegate;

    public BiscuitJarAdapter(BiscuitJar delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        return delegate.loadForRequest(url);
    }

    @Override
    public void saveFromResponse(
            HttpUrl url,
            List<Cookie> cookies
    ) {
        delegate.saveFromResponse(url, cookies);
    }
}