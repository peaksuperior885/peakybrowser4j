package com.peak885.peakybrowser4jv2.browser;

import java.nio.file.Path;
import okhttp3.HttpUrl;

/** Shared location for user-requested browser diagnostic artifacts. */
public final class DiagnosticPaths {
    private DiagnosticPaths() {}

    public static Path directory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local", "PeakBrowser")
                : Path.of(localAppData, "PeakBrowser");
        return root.resolve("diagnostics");
    }

    /** URL label for logs: retain the destination, never query values or data payloads. */
    public static String safeUrl(String value) {
        if (value == null || value.isBlank()) return "unknown URL";
        if (value.regionMatches(true, 0, "data:", 0, 5)) return "data:<redacted>";
        HttpUrl url = HttpUrl.parse(value);
        if (url == null) return "non-http URL";
        StringBuilder safe = new StringBuilder(url.scheme()).append("://").append(url.host());
        int defaultPort = "https".equals(url.scheme()) ? 443 : 80;
        if (url.port() != defaultPort) safe.append(':').append(url.port());
        safe.append(url.encodedPath());
        if (url.querySize() > 0) safe.append("?<query-redacted>");
        return safe.toString();
    }

    public static String safeHeaderValue(String name, String value) {
        if (name == null || value == null) return value;
        if (name.matches("(?i).*(cookie|authorization|token|secret|api[-_]?key).*")) {
            return "<redacted>";
        }
        if ("referer".equalsIgnoreCase(name)) return safeUrl(value);
        return value;
    }
}
