package com.peak885.peakybrowser4jv2.browser.http;

import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class UserAgent {

    private UserAgent() {
    }

    public static String build() {
        return "Mozilla/5.0 (" +
                platform() +
                ") AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36 " +
                "PeakyBrowser/2.0";
    }

    private static String platform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osVersion = System.getProperty("os.version", "10.0");
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String archToken = isArm(arch) ? "ARM64" : "Win64; x64";

        // ── 1. WINDOWS 10 / 11 SPECIFIC DETECTION SYSTEM ──
        if (osName.contains("win")) {
            // Windows 11 is defined by builds >= 22000
            if (isWindows11ByBuild()) {
                return "Windows NT 10.0; " + archToken; // User Agent spec requires Windows 11 to output NT 10.0!
            }
            return "Windows NT " + osVersion + "; " + archToken;
        }

        // ── 2. MACOS DARWIN VERSION MATRIX RESOLUTION ──
        if (osName.contains("mac") || osName.contains("darwin")) {
            String macVersion = osVersion.replace('.', '_');
            // If Java returns a generic default version token, fall back to a high compatibility profile
            if (macVersion.equals("10_0") || macVersion.isBlank()) {
                macVersion = "10_15_7";
            }
            return "Macintosh; Intel Mac OS X " + macVersion;
        }

        // ── 3. LINUX ENVIRONMENT STRUCTURAL KERNEL SCANNING ──
        if (osName.contains("linux")) {
            String linuxArch = isArm(arch) ? "aarch64" : "x86_64";
            return "X11; Linux " + linuxArch;
        }

        return "X11; " + System.getProperty("os.name", "Unknown");
    }

    /**
     * Inspects the absolute kernel registry version layout to isolate Windows 11 instances.
     */
    private static boolean isWindows11ByBuild() {
        try {
            // Read OS compilation metrics directly via internal system properties
            String buildNumberStr = System.getProperty("os.build.number");
            if (buildNumberStr != null && !buildNumberStr.isBlank()) {
                int build = Integer.parseInt(buildNumberStr.trim());
                return build >= 22000;
            }

            // Fallback: Query system management instrumentation using runtime execution pipelines
            Process process = Runtime.getRuntime().exec("cmd.exe /c ver");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("[")) {
                        // Extracts the build string inside e.g., "Microsoft Windows [Version 10.0.22631]"
                        String versionPart = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
                        String[] tokens = versionPart.split("\\.");
                        if (tokens.length >= 4) {
                            int build = Integer.parseInt(tokens[3]);
                            return build >= 22000;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fail gracefully if system container gates block execution commands
        }
        return false;
    }

    private static boolean isArm(String arch) {
        return arch.contains("aarch64")
                || arch.contains("arm64")
                || arch.contains("arm");
    }
}
