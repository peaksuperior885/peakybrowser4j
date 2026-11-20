package com.peak885.peakybrowser4jv2.browser.js.processing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsAsyncTransformer {

    private JsAsyncTransformer() {
    }

    public static String transform(String script) {
        if (script == null || script.isBlank()) {
            return script;
        }

        String result = transformAsyncFunctions(script);
        result = transformAsyncArrows(result);

        return result;
    }

    /**
     * Strips "async" wherever it directly precedes an arrow function
     * signature - "async () => {...}", "async (a, b) => {...}", and the
     * single-bare-parameter form "async x => {...}".
     *
     * Rhino has no idea what to do with the "async" keyword sitting in
     * front of an arrow function: it tries to reinterpret the parameter
     * list mid-parse and throws "missing formal parameter", which aborts
     * compilation of the WHOLE script, not just this one function.
     *
     * Plain (non-async) arrow functions already work fine in this Rhino
     * version, so dropping "async" is enough to make the signature
     * parse; any "await" left inside the body is handled later by
     * JsPreprocessor's blanket "\bawait\s+" strip.
     */
    private static String transformAsyncArrows(String script) {
        Pattern pattern = Pattern.compile(
                "\\basync\\s*"
                        + "(\\([^()]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)"
                        + "\\s*=>"
        );

        Matcher matcher = pattern.matcher(script);

        return matcher.replaceAll(mr -> mr.group(1) + " =>");
    }

    private static String transformAsyncFunctions(String script) {
        Pattern pattern = Pattern.compile(
                "\\basync\\s+function\\s+"
                        + "([A-Za-z_$][A-Za-z0-9_$]*)"
                        + "\\s*\\("
        );

        Matcher matcher = pattern.matcher(script);

        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            String functionName = matcher.group(1);

            int bodyStart = findFunctionBodyStart(
                    script,
                    matcher.end()
            );

            if (bodyStart == -1) {
                continue;
            }

            int bodyEnd = findMatchingBrace(
                    script,
                    bodyStart
            );

            if (bodyEnd == -1) {
                continue;
            }

            // Preserve everything before this async function.
            result.append(
                    script,
                    lastEnd,
                    matcher.start()
            );

            String parametersAndHeader =
                    script.substring(
                            matcher.end(),
                            bodyStart
                    );

            String body =
                    script.substring(
                            bodyStart + 1,
                            bodyEnd
                    );

            String transformedBody =
                    transformAwait(body);

            String replacement =
                    ";function "
                            + functionName
                            + "("
                            + extractParameters(parametersAndHeader)
                            + ") {"
                            + transformedBody
                            + "}";

            result.append(replacement);

            // Skip the entire original function.
            lastEnd = bodyEnd + 1;

            // IMPORTANT:
            // Search from after the function we just consumed.
            matcher.region(lastEnd, script.length());
        }

        // Append whatever remains after the final async function.
        result.append(script, lastEnd, script.length());

        return result.toString();
    }

    private static String extractParameters(String header) {
        int close = header.lastIndexOf(')');
        if (close == -1) {
            return header.trim();
        }

        return header.substring(0, close).trim();
    }

    /** Find the '{' that opens the function body, respecting strings / regex / templates. */
    private static int findFunctionBodyStart(String script, int start) {
        ScanState st = new ScanState();
        for (int i = start; i < script.length(); i++) {
            if (st.consume(script, i)) {
                continue;
            }
            if (script.charAt(i) == '{') {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String script, int openingBrace) {
        int depth = 0;
        ScanState st = new ScanState();

        for (int i = openingBrace; i < script.length(); i++) {
            if (st.consume(script, i)) {
                continue;
            }
            char c = script.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Tracks whether the scanner is inside a string, template literal,
     * comment, or regex literal so braces inside them do not affect matching.
     * <p>
     * Critical for page scripts that contain patterns like
     * {@code .replace(/"/g, ...)} — without regex awareness the {@code "}
     * opens a fake string and the function body never appears to close, so
     * the async transform is skipped and {@code await} is later stripped,
     * racing the caller past the Promise.
     */
    private static final class ScanState {
        private static final int CODE = 0;
        private static final int STRING = 1;
        private static final int TEMPLATE = 2;
        private static final int REGEX = 3;
        private static final int LINE_COMMENT = 4;
        private static final int BLOCK_COMMENT = 5;

        private int mode = CODE;
        private char stringChar;
        private boolean escaped;
        private boolean inRegexClass;
        /** Depth of nested ${} expressions inside a template literal. */
        private int templateExprDepth;
        /** True when the current STRING was opened inside a template ${}. */
        private boolean stringFromTemplate;

        /**
         * @return true if the current character is inside a non-code region
         *         (caller should not treat it as a structural brace)
         */
        boolean consume(String script, int i) {
            char c = script.charAt(i);

            switch (mode) {
                case STRING -> {
                    if (escaped) {
                        escaped = false;
                        return true;
                    }
                    if (c == '\\') {
                        escaped = true;
                        return true;
                    }
                    if (c == stringChar) {
                        mode = stringFromTemplate ? TEMPLATE : CODE;
                        stringFromTemplate = false;
                    }
                    return true;
                }
                case TEMPLATE -> {
                    if (escaped) {
                        escaped = false;
                        return true;
                    }
                    if (c == '\\') {
                        escaped = true;
                        return true;
                    }
                    if (templateExprDepth == 0) {
                        if (c == '`') {
                            mode = CODE;
                            return true;
                        }
                        if (c == '$' && i + 1 < script.length()
                                && script.charAt(i + 1) == '{') {
                            // ${ starts an expression — braces inside must count.
                            // Stay in TEMPLATE mode but raise expr depth; the
                            // following '{' is still "inside template" for the
                            // string sense, but we return false so caller depth++.
                            templateExprDepth = 1;
                            return true; // consume '$'; '{' handled next iter
                        }
                        return true;
                    }
                    // Inside ${ ... }: structural braces count for the
                    // outer function matcher, so return false for { }.
                    if (c == '{') {
                        templateExprDepth++;
                        return false;
                    }
                    if (c == '}') {
                        templateExprDepth--;
                        return false;
                    }
                    // Still need to track nested strings inside the expression
                    if (c == '"' || c == '\'') {
                        mode = STRING;
                        stringChar = c;
                        stringFromTemplate = true;
                        escaped = false;
                        return true;
                    }
                    return true;
                }
                case REGEX -> {
                    if (escaped) {
                        escaped = false;
                        return true;
                    }
                    if (c == '\\') {
                        escaped = true;
                        return true;
                    }
                    if (c == '[') {
                        inRegexClass = true;
                        return true;
                    }
                    if (c == ']' && inRegexClass) {
                        inRegexClass = false;
                        return true;
                    }
                    if (c == '/' && !inRegexClass) {
                        mode = CODE;
                    }
                    return true;
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r') {
                        mode = CODE;
                    }
                    return true;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < script.length()
                            && script.charAt(i + 1) == '/') {
                        // Will still be in BLOCK_COMMENT for the '/', which is fine.
                        // Advance: set a one-shot to close on next char.
                        // Simpler: check pair and if next is /, close after
                        // treating both as comment. On '*', peek '/'.
                        // Close at the '/' by setting mode on '*" when next is /.
                        // For '*', stay; for following '/', exit:
                    }
                    // Close when we see '/' and previous was '*'
                    if (c == '/' && i > 0 && script.charAt(i - 1) == '*') {
                        mode = CODE;
                    }
                    return true;
                }
                default -> {
                    // CODE
                    if (c == '"' || c == '\'') {
                        mode = STRING;
                        stringChar = c;
                        escaped = false;
                        stringFromTemplate = false;
                        return true;
                    }
                    if (c == '`') {
                        mode = TEMPLATE;
                        templateExprDepth = 0;
                        escaped = false;
                        return true;
                    }
                    if (c == '/' && i + 1 < script.length()) {
                        char n = script.charAt(i + 1);
                        if (n == '/') {
                            mode = LINE_COMMENT;
                            return true;
                        }
                        if (n == '*') {
                            mode = BLOCK_COMMENT;
                            return true;
                        }
                        if (looksLikeRegexStart(script, i)) {
                            mode = REGEX;
                            inRegexClass = false;
                            escaped = false;
                            return true;
                        }
                    }
                    // Returning from a string that was nested in a template
                    // expression: if templateExprDepth > 0 and we just left
                    // STRING, mode is already CODE here — restore TEMPLATE.
                    // (Handled by not switching to CODE from STRING when
                    // templateExprDepth > 0 — fixed below in STRING exit.)
                    return false;
                }
            }
        }
    }

    private static boolean looksLikeRegexStart(String script, int slashIndex) {
        int j = slashIndex - 1;
        while (j >= 0 && Character.isWhitespace(script.charAt(j))) {
            j--;
        }
        if (j < 0) {
            return true;
        }
        char p = script.charAt(j);
        // After identifier / number / ) ] } → division, not regex.
        if (Character.isLetterOrDigit(p) || p == '_' || p == '$'
                || p == ')' || p == ']' || p == '}') {
            return false;
        }
        return true;
    }

    /**
     * Desugar the first top-level "await EXPR" (including the common
     * try { await EXPR; } catch (e) { ... } pattern) into a .then() chain.
     */
    private static String transformAwait(String body) {
        // Look for: try { ... await EXPR; ... } catch (...) { ... }
        Pattern tryAwaitPattern = Pattern.compile(
                "try\\s*\\{" +                          // try {
                        "([^\\}]*?)" +                          // anything before the await (non-greedy)
                        "\\bawait\\s+([^;]+);" +                // await EXPR;
                        "([^\\}]*)" +                           // anything after the await inside the try
                        "\\}" +                                 // }
                        "\\s*catch\\s*\\(([^\\)]*)\\)\\s*\\{" + // catch (e) {
                        "([\\s\\S]*?)" +                        // catch body
                        "\\}"                                   // }
        );

        Matcher tryMatcher = tryAwaitPattern.matcher(body);

        if (tryMatcher.find()) {
            String beforeAwaitInTry = tryMatcher.group(1);
            String expression       = tryMatcher.group(2).trim();
            String afterAwaitInTry  = tryMatcher.group(3);
            String catchParam       = tryMatcher.group(4).trim();
            String catchBody        = tryMatcher.group(5);

            int start = tryMatcher.start();
            int end   = tryMatcher.end();

            String before = body.substring(0, start);
            String after  = body.substring(end);          // everything after the whole try/catch

            // Build the .then() callback = (code after await inside try) + (code after the try/catch)
            String thenBody = afterAwaitInTry + after;

            // Recursively transform any further awaits that may still be present
            thenBody = transformAwait(thenBody);

            String result =
                    before +
                            "return " + expression + ".then(function() {" +
                            thenBody +
                            "}).catch(function(" + catchParam + ") {" +
                            catchBody +
                            "});";

            return result;
        }

        // ---------- Fallback: plain top-level await (no try/catch) ----------
        Pattern plainPattern = Pattern.compile("\\bawait\\s+([^;]+);");
        Matcher plainMatcher = plainPattern.matcher(body);

        while (plainMatcher.find()) {
            int awaitStart = plainMatcher.start();
            int depth = braceDepth(body, awaitStart);

            if (depth != 0) {
                // nested – leave it for a later pass / more advanced handling
                continue;
            }

            String expression = plainMatcher.group(1).trim();
            String before = body.substring(0, awaitStart);
            String after  = body.substring(plainMatcher.end());

            // Handle:  var/let/const x = await expr;
            Pattern assignmentPattern = Pattern.compile(
                    "(var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*$");
            Matcher assignment = assignmentPattern.matcher(before);

            if (assignment.find()) {
                String varName = assignment.group(2);
                String assignmentBefore = before.substring(0, assignment.start());

                return assignmentBefore
                        + "return " + expression + ".then(function(" + varName + ") {"
                        + transformAwait(after)
                        + "});";
            }

            // Plain await
            return before
                    + "return " + expression + ".then(function() {"
                    + transformAwait(after)
                    + "});";
        }

        return body;
    }

    private static int findContainingBlockStart(String text, int position) {
        int depth = 0;
        int lastBlockStart = -1;

        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = 0; i < position; i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == stringChar) {
                    inString = false;
                }

                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }

            if (c == '{') {
                depth++;
                lastBlockStart = i;
            } else if (c == '}') {
                depth--;
            }
        }

        return depth > 0 ? lastBlockStart : -1;
    }

    private static int braceDepth(String text, int end) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == stringChar) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }
}
