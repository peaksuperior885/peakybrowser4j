package com.peak885.peakybrowser4jv2.browser.cookiemonster;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import java.util.List;

public interface BiscuitJar {
    List<Cookie> loadForRequest(HttpUrl url);

    void saveFromResponse(
            HttpUrl url,
            List<Cookie> cookies
    );
}