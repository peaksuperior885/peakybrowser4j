package com.peak885.peakybrowser4jv2.browser.js.processing;

/**
 * Entry point for Rhino-facing JS rewrites. Real work lives in the focused
 * transformers this class orchestrates.
 */
public final class JsPreprocessor {

    private JsPreprocessor() {
    }

    public static String process(String script) {
        if (script == null || script.isBlank()) {
            return script;
        }

        String processed = script;

        processed = processed.replaceAll(
                "this\\.gbar_\\s*=\\s*this\\.gbar_\\s*\\|\\|\\s*\\{\\s*\\}",
                "this.gbar_=this.gbar_||{_DumpException:__browserDumpException}"
        );

        processed = processed.replaceAll(
                "[A-Za-z_$][A-Za-z0-9_$]*\\._DumpException\\s*\\(",
                "__browserDumpException("
        );

        processed = JsClassTransformer.transform(processed);
        checkForCorruption("JsClassTransformer", processed);

        processed = JsSyntaxCompat.transformRestAndSpread(processed);
        checkForCorruption("transformRestAndSpread", processed);

        processed = JsAsyncTransformer.transform(processed);
        checkForCorruption("JsAsyncTransformer", processed);

        processed = JsLoopTransformer.transformForEach(processed);
        checkForCorruption("transformForEach", processed);

        processed = JsLoopTransformer.transformForEachExpression(processed);
        checkForCorruption("transformForEachExpression", processed);

        processed = JsLoopTransformer.transformForOf(processed);
        checkForCorruption("transformForOf", processed);

        processed = JsSyntaxCompat.stripDefaultParameters(processed);
        checkForCorruption("stripDefaultParameters", processed);

        processed = JsSyntaxCompat.transformSimpleNullish(processed);
        checkForCorruption("transformSimpleNullish", processed);

        processed = JsSyntaxCompat.normalizeKeywords(processed);
        checkForCorruption("normalizeKeywords", processed);

        return processed;
    }

    private static void checkForCorruption(String stage, String script) {
        String marker = "void 0.";

        int index = script.indexOf(marker);
        if (index >= 0) {
            int start = Math.max(0, index - 120);
            int end = Math.min(script.length(), index + 180);

            System.out.println(
                    "[JS PREPROCESSOR] Suspicious output after "
                            + stage + ":\n"
                            + script.substring(start, end)
            );
        }
    }
}