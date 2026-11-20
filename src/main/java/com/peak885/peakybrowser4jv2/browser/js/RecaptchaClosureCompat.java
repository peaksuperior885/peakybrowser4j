package com.peak885.peakybrowser4jv2.browser.js;

import org.tinylog.Logger;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Focused compatibility patches for Google's generated, Closure-compiled
 * reCAPTCHA bundles.
 */
public final class RecaptchaClosureCompat {

    private static final Pattern LISTENER_CLEANUP = Pattern.compile(
            "([\\w$]+)\\.na=([\\w$]+),\\1\\.listener=null,\\1\\.proxy=null,\\1\\.src=null,\\1\\.handler=null"
    );

    /** Guards Google's generated getComputedStyle helper when ownerDocument is absent. */
    private static final Pattern OWNER_DOCUMENT_STYLE_LOOKUP = Pattern.compile(
            "(\\b([\\w$]+)\\s*=\\s*[\\w$]+\\[\\d+\\]\\(\\d+,\\s*[\\w$]+\\)\\s*,\\s*)"
                    + "\\2\\.defaultView&&\\2\\.defaultView\\.getComputedStyle"
    );

    private static final Pattern HAS_ATTRIBUTE_CALL = Pattern.compile(
            "([\\w$]+)\\s*\\.\\s*hasAttribute\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)"
    );

    private static final Pattern LEGACY_LISTENER_CALL =
            Pattern.compile("MB\\(51,37,a,U\\[m\\]\\)");

    /**
     * Rewrite every {@code X.bind(...)} in the bundle into {@code __pbRecaptchaBind(X, ...)}.
     *
     * This regex rewrite cannot know what X really is, and it had to be introduced when
     * Object.prototype.bind and proto-less Java functions made real .bind unreliable. Both are
     * fixed now (non-enumerable Function.prototype.bind + the native-function wrap in JsBridge),
     * so the rewrite is OFF by default: reCAPTCHA's component code ended up with the helper's
     * wrapper function where a DOM element should be (insertBefore(function,null), then
     * "Cannot find function setAttribute"). Flip to true to get the old behaviour back.
     */
    private static final boolean REWRITE_BIND_CALLS = false;

    private static final Pattern BIND_CALL = Pattern.compile(
            "([A-Za-z_$][\\w$]*(?:\\s*\\[[^\\]]+\\]|\\s*\\([^\\)]*\\)|\\s*\\.[\\w$]+)*)\\.bind\\("
    );

    private static final Pattern LISTEN_THROW = Pattern.compile(
            "else\\s+if\\s*\\(\\s*([\\w$]+)\\.addListener\\s*&&\\s*\\1\\.removeListener\\s*\\)"
                    + "\\s*\\1\\.addListener\\(([\\w$]+)\\)\\s*;"
                    + "\\s*else\\s+throw\\s+Error\\(\\s*\"addEventListener and attachEvent are unavailable\\.\"\\s*\\)"
    );

    /**
     * Google creates a JSON.parse alias like:
     *
     * NAME=($T=self)==null?void 0:
     *     (Ke=$T.JSON)==null?void 0:
     *     (Xd=Ke.parse)==null?void 0:
     *     (oX=Xd.bind)==null?void 0:
     *     oX.call(Xd,JSON)
     *
     * NAME is minified and CHANGES BETWEEN RELEASES (it was "lQ" once, it is "zl"
     * in release 4EDjxXf4knsx6UBM1L2V6cv8). The replacement below must therefore
     * reuse the name the bundle actually assigns, never a hardcoded one: hardcoding
     * "lQ" silently deleted the real "zl" definition and made
     * recaptcha.anchor.Main.init die with: ReferenceError: "zl" is not defined.
     *
     * Rhino's generated/bound-function behavior can cause the original to resolve
     * incorrectly. Our JsJson implementation already provides JSON.parse(),
     * so replace the whole binding with a small direct wrapper.
     */
    private static final Pattern LQ_JSON_PARSE =
            Pattern.compile(
                    "(?<![\\w$])([A-Za-z_$][\\w$]*)\\s*=\\s*\\(([A-Za-z_$][\\w$]*)=self\\)==null\\?void 0:"
                            + "\\(([A-Za-z_$][\\w$]*)=\\2\\.JSON\\)==null\\?void 0:"
                            + "\\(([A-Za-z_$][\\w$]*)=\\3\\.parse\\)==null\\?void 0:"
                            + "\\(([A-Za-z_$][\\w$]*)=\\4\\.bind\\)==null\\?void 0:"
                            + "\\5\\.call\\(\\4,JSON\\)"
            );

    /**
     * Matches the widget-constructor bind-parameter check that throws
     * "The bind parameter must be an element or id" when the resolved
     * container is null.
     */
    private static final Pattern BIND_PARAM_THROW = Pattern.compile(
            "if\\s*\\(\\s*(?:"
                    + "!\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*=\\s*(.+?),\\s*\\1\\s*\\)"
                    + "|"
                    + "([A-Za-z_$][\\w$]*)\\s*=\\s*(.+?),\\s*!\\s*\\3"
                    + ")\\s*\\)\\s*throw\\s+Error\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*\\(\\s*\\)\\s*\\)"
    );

    private RecaptchaClosureCompat() {
    }

    public static void installEvalPatch(Context cx, Scriptable scope) {
        ScriptableObject.putProperty(
                scope,
                "__peakyPatchRecaptchaEval",
                new BaseFunction() {
                    @Override
                    public Object call(
                            Context context,
                            Scriptable callScope,
                            Scriptable thisObj,
                            Object[] args
                    ) {
                        return patchGeneratedSource(
                                args.length == 0
                                        ? null
                                        : Context.toString(args[0])
                        );
                    }
                }
        );
    }

    /**
     * These bundles are already Closure-compiled; general Rhino
     * preprocessing damages them.
     */
    public static boolean isRawBundle(String scriptUrl) {
        if (scriptUrl == null || scriptUrl.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(scriptUrl);

            String host = uri.getHost();
            String path = uri.getPath();

            if (host == null || path == null) {
                return false;
            }

            host = host.toLowerCase(Locale.ROOT);
            path = path.toLowerCase(Locale.ROOT);

            return (
                    (host.equals("www.google.com") || host.equals("google.com"))
                            && path.equals("/recaptcha/enterprise.js")
            ) || (
                    (host.equals("www.gstatic.com") || host.equals("gstatic.com"))
                            && path.startsWith("/recaptcha/releases/")
            );

        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Guards the sparse-listener cleanup failure observed in Google's
     * generated eval.
     */
    public static String patchGeneratedSource(String source) {
        if (source == null || source.length() < 20_000) {
            return source;
        }

        Matcher legacy = LEGACY_LISTENER_CALL.matcher(source);

        StringBuffer out = new StringBuffer();
        int legacyCount = 0;

        while (legacy.find()) {
            legacy.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            "(U[m] == null ? void 0 : " + legacy.group() + ")"
                    )
            );

            legacyCount++;
        }

        legacy.appendTail(out);

        String interim = out.toString();

        Matcher cleanup = LISTENER_CLEANUP.matcher(interim);

        out = new StringBuffer();
        int cleanupCount = 0;

        while (cleanup.find()) {
            String sequence = cleanup.group();
            String record = cleanup.group(1);

            cleanup.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            record + "&&(" + sequence + ")"
                    )
            );

            cleanupCount++;
        }

        cleanup.appendTail(out);

        String guarded = guardOwnerDocumentStyleLookup(rewriteHasAttributeCalls(out.toString()));

        if (legacyCount > 0) {
            Logger.warn(
                    "[RECAPTCHA-CLOSURE] guarded {} legacy listener cleanup call(s)",
                    legacyCount
            );
        }

        if (cleanupCount > 0) {
            Logger.warn(
                    "[RECAPTCHA-CLOSURE] guarded {} empty listener-record cleanup sequence(s)",
                    cleanupCount
            );
        }

        return guarded;
    }

    private static String guardOwnerDocumentStyleLookup(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = OWNER_DOCUMENT_STYLE_LOOKUP.matcher(source);
        StringBuffer output = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            String variable = matcher.group(2);
            matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement(
                            matcher.group(1) + variable + "&&" + variable + ".defaultView&&"
                                    + variable + ".defaultView.getComputedStyle"
                    )
            );
            count++;
        }
        matcher.appendTail(output);
        if (count > 0) {
            Logger.info(
                    "[RECAPTCHA-CLOSURE] guarded {} missing owner-document style lookup(s)",
                    count
            );
        }
        return output.toString();
    }

    private static String rewriteHasAttributeCalls(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = HAS_ATTRIBUTE_CALL.matcher(source);
        StringBuffer output = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            String node = matcher.group(1);
            String attribute = matcher.group(2);
            matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement(
                            "(" + node + "!=null&&((typeof " + node + ".hasAttribute==='function'&&"
                                    + node + ".hasAttribute(" + attribute + "))||(typeof "
                                    + node + ".hasAttr==='function'&&" + node + ".hasAttr(" + attribute
                                    + "))||(typeof " + node + ".getAttribute==='function'&&"
                                    + node + ".getAttribute(" + attribute + ")!=null)))"
                    )
            );
            count++;
        }
        matcher.appendTail(output);
        if (count > 0) {
            Logger.info("[RECAPTCHA-CLOSURE] added {} compatible attribute check(s)", count);
        }
        return output.toString();
    }

    /**
     * Rewrite the raw downloaded reCAPTCHA bundle and install the
     * browser-side helper when needed.
     */
    public static String prepareBundle(
            String script,
            Runnable installRuntimeHelpers
    ) {
        if (script == null || script.isBlank()) {
            return script;
        }

        // This failure is in the downloaded release bundle itself, before eval.
        script = guardOwnerDocumentStyleLookup(script);
        script = rewriteHasAttributeCalls(script);

        /*
         * ============================================================
         * lQ -> JSON.parse COMPATIBILITY
         * ============================================================
         *
         * Do this BEFORE the generic .bind() rewrite.
         *
         * Google defines lQ using JSON.parse.bind(JSON). We already
         * provide a Rhino-compatible JSON.parse through JsJson, so
         * bypass the generated bind expression entirely.
         */

        Matcher lqMatcher = LQ_JSON_PARSE.matcher(script);

        StringBuffer lqOutput = new StringBuffer();
        int lqFixes = 0;

        while (lqMatcher.find()) {
            lqMatcher.appendReplacement(
                    lqOutput,
                    Matcher.quoteReplacement(
                            lqMatcher.group(1) + "=function(x,r){return JSON.parse(x,r)}"
                    )
            );

            Logger.info(
                    "[RECAPTCHA-CLOSURE] JSON.parse alias in this release is '{}'",
                    lqMatcher.group(1)
            );

            lqFixes++;
        }

        lqMatcher.appendTail(lqOutput);

        script = lqOutput.toString();

        if (lqFixes > 0) {
            Logger.info(
                    "[RECAPTCHA-CLOSURE] patched {} JSON.parse alias binding(s)",
                    lqFixes
            );
        }

        /*
         * ============================================================
         * CLOSURE .bind() COMPATIBILITY
         * ============================================================
         */

        Matcher matcher = BIND_CALL.matcher(script);

        StringBuffer output = new StringBuffer();
        int bindRewrites = 0;

        while (REWRITE_BIND_CALLS && matcher.find()) {
            String expression = matcher.group(1);

            if (expression.contains("__pbRecaptchaBind")) {
                continue;
            }

            matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement(
                            "__pbRecaptchaBind(" + expression + ", "
                    )
            );

            bindRewrites++;
        }

        matcher.appendTail(output);

        if (!REWRITE_BIND_CALLS) {
            Logger.info("[RECAPTCHA-CLOSURE] generic .bind() rewrite is disabled (REWRITE_BIND_CALLS=false)");
        }

        String intermediate = output.toString();

        /*
         * ============================================================
         * CLOSURE LISTENER FAILURE PATH
         * ============================================================
         */

        String rewritten = LISTEN_THROW.matcher(intermediate).replaceAll(
                "else if($1.addListener&&$1.removeListener)"
                        + "$1.addListener($2);"
                        + "else throw __peakyListenError($1)"
        );

        boolean listenRewrite = !rewritten.equals(intermediate);

        /*
         * ============================================================
         * RECAPTCHA BIND-PARAMETER FALLBACK
         * ============================================================
         */

        Matcher bindParam = BIND_PARAM_THROW.matcher(rewritten);

        StringBuffer bindParamOut = new StringBuffer();
        int bindParamFixes = 0;

        while (bindParam.find()) {
            String var =
                    bindParam.group(1) != null
                            ? bindParam.group(1)
                            : bindParam.group(3);

            String resolveExpr =
                    bindParam.group(2) != null
                            ? bindParam.group(2)
                            : bindParam.group(4);

            String msgFn = bindParam.group(5);

            boolean looksLikeBindResolve =
                    resolveExpr != null
                            && (
                            resolveExpr.contains(".get(")
                                    || resolveExpr.contains("getElementById")
                                    || (
                                    resolveExpr.contains("(")
                                            && resolveExpr.contains(",")
                                            && resolveExpr.length() < 80
                            )
                    );

            if (looksLikeBindResolve && var != null && msgFn != null) {

                String replacement =
                        "if(!(" + var + "=" + resolveExpr + "," + var + "))"
                                + "{"
                                + var
                                + "=(typeof document!=='undefined'"
                                + "&&document.querySelector&&("
                                + "document.querySelector('.g-recaptcha')"
                                + "||document.querySelector('#recaptcha')"
                                + "||document.getElementById('recaptcha')"
                                + "))"
                                + ";if(!"
                                + var
                                + ")throw Error("
                                + msgFn
                                + "())"
                                + "}";

                bindParam.appendReplacement(
                        bindParamOut,
                        Matcher.quoteReplacement(replacement)
                );

                bindParamFixes++;

            } else {
                bindParam.appendReplacement(
                        bindParamOut,
                        Matcher.quoteReplacement(bindParam.group())
                );
            }
        }

        bindParam.appendTail(bindParamOut);

        if (bindParamFixes > 0) {
            rewritten = bindParamOut.toString();

            Logger.info(
                    "[RECAPTCHA-CLOSURE] added g-recaptcha fallback for {} bind-parameter check(s)",
                    bindParamFixes
            );
        }

        /*
         * ============================================================
         * LOG PATCH RESULTS
         * ============================================================
         */

        if (bindRewrites > 0) {
            Logger.info(
                    "[RECAPTCHA-CLOSURE] rewrote {} bind calls",
                    bindRewrites
            );
        }

        if (listenRewrite) {
            Logger.info(
                    "[RECAPTCHA-CLOSURE] instrumented the Closure listen failure path"
            );
        }

        /*
         * ============================================================
         * INSTALL RUNTIME HELPERS
         * ============================================================
         */

        if (
                (
                        lqFixes > 0
                                || bindRewrites > 0
                                || listenRewrite
                                || bindParamFixes > 0
                )
                        && installRuntimeHelpers != null
        ) {
            installRuntimeHelpers.run();
        }

        return rewritten;
    }
}