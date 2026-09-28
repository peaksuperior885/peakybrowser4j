package com.peak885.peakybrowser4jv2.browser;

import com.formdev.flatlaf.FlatDarkLaf;
import com.helger.css.reader.CSSReader;
import com.helger.css.handler.ICSSParseExceptionCallback;
import org.tinylog.Logger;

import javax.swing.SwingUtilities;

public final class BrowserApplication {

    static {
        // ── THE CRITICAL FIX: Intercept the ph-css parser thread crash globally ──
        try {
            CSSReader.setDefaultParseExceptionHandler(new ICSSParseExceptionCallback() {
                @Override
                public void onException(final com.helger.css.parser.ParseException ex) {
                    // Route it safely via tinylog, preventing the internal NullPointerException!
                    Logger.warn("[CSS Spec Deviation] Skipped malformed syntax block: {}",
                            ex != null ? ex.getMessage() : "Malformed token layout configuration");
                }
            });
            Logger.info("[CSS Engine] Global safe exception handler override successful");
        } catch (Throwable t) {
            Logger.error(t, "Failed to apply global CSS parser exception fallback handler");
        }
    }

    private BrowserApplication() {
    }

    public static void start(String url) {
        System.setProperty("flatlaf.useWindowDecorations", "false");

        if (url == null || url.isBlank()) {
            return;
        }

        FlatDarkLaf.setup();

        SwingUtilities.invokeLater(() -> {

            BrowserWindow window =
                    new BrowserWindow(url);

            window.show();
        });
    }
}
