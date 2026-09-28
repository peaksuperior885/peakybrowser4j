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
        processed = JsSyntaxCompat.transformRestAndSpread(processed);
        processed = JsAsyncTransformer.transform(processed);
        processed = JsLoopTransformer.transformForEach(processed);
        processed = JsLoopTransformer.transformForEachExpression(processed);
        processed = JsLoopTransformer.transformForOf(processed);
        processed = JsSyntaxCompat.stripDefaultParameters(processed);
        processed = JsSyntaxCompat.transformSimpleNullish(processed);
        processed = JsSyntaxCompat.normalizeKeywords(processed);

        return processed;
    }
}