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
        return transformAsyncFunctions(script);
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
                    "function "
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

    /** Find the '{' that opens the function body, respecting strings. */
    private static int findFunctionBodyStart(String script, int start) {
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = start; i < script.length(); i++) {
            char c = script.charAt(i);
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
            if (c == '{') return i;
        }
        return -1;
    }

    private static int findMatchingBrace(String script, int openingBrace) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = openingBrace; i < script.length(); i++) {
            char c = script.charAt(i);
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
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
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