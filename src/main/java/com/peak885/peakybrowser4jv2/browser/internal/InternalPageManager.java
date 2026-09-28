package com.peak885.peakybrowser4jv2.browser.internal;

public final class InternalPageManager {

    private InternalPageManager() {
    }

    public static boolean isInternal(String url) {
        return url != null
                && url.regionMatches(
                true,
                0,
                "internal:",
                0,
                "internal:".length()
        );
    }

    public static String resolve(String url) {
        if (!isInternal(url)) {
            return null;
        }

        String page = url.substring("internal:".length())
                .trim()
                .toLowerCase();

        return switch (page) {
            case "newtab" -> "/pages/newtab/index.html";

            default -> throw new IllegalArgumentException(
                    "Unknown internal page: " + page
            );
        };
    }
}