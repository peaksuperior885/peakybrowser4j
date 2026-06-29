package com.peak885.peakybrowser4j.browser.css;

import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.reader.CSSReader;
import com.helger.css.handler.LoggingCSSParseExceptionCallback;
import org.tinylog.Logger;
import java.nio.charset.StandardCharsets;

public class CssLoader {

    public CascadingStyleSheet load(String css) {
        if (css == null) {
            Logger.warn("CssLoader.load() called with null CSS");
            return new CascadingStyleSheet();
        }

        if (css.isEmpty()) {
            Logger.warn("CssLoader.load() called with empty CSS");
            return new CascadingStyleSheet();
        }

        Logger.info("CssLoader: Parsing {} chars of CSS", css.length());

        CascadingStyleSheet sheet = null;

        try {
            // Try newer API first (ph-css 6.0+)
            sheet = CSSReader.readFromString(
                    css,
                    StandardCharsets.UTF_8,
                    new LoggingCSSParseExceptionCallback()
            );
        } catch (Exception e) {
            Logger.warn("Newer API failed, trying fallback: {}", e.getMessage());
            try {
                // Fallback for older versions
                sheet = CSSReader.readFromString(
                        css,
                        new LoggingCSSParseExceptionCallback()
                );
            } catch (Exception e2) {
                Logger.error("Both CSS parsing methods failed: {}", e2.getMessage());
                return new CascadingStyleSheet();
            }
        }

        if (sheet == null) {
            Logger.error("CSS PARSE RETURNED NULL");
            return new CascadingStyleSheet();
        }

        int ruleCount = sheet.getStyleRuleCount();
        Logger.info("CssLoader: Successfully parsed {} style rules", ruleCount);

        if (ruleCount == 0) {
            Logger.warn("CssLoader: Stylesheet has zero rules (might be malformed or all comments)");
        }

        return sheet;
    }
}