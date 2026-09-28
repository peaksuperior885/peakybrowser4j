package com.peak885.peakybrowser4jv2.browser.js.processing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers {@code for...of} and {@code forEach} arrow callbacks to plain
 * indexed {@code for} loops that Rhino can run.
 */
final class JsLoopTransformer {

    private JsLoopTransformer() {
    }

    static String transformForOf(String script) {
        Pattern pattern = Pattern.compile(
                "(?:^|[;{}\\n])\\s*" +
                        "for\\s*\\(\\s*" +
                        "(?:const|let|var)\\s+" +
                        "([A-Za-z_$][A-Za-z0-9_$]*)" +
                        "\\s+of\\s+" +
                        "([^\\)]+)" +
                        "\\)\\s*\\{"
        );

        Matcher matcher = pattern.matcher(script);
        StringBuffer result = new StringBuffer();
        int loopCounter = 0;

        while (matcher.find()) {
            String variable = matcher.group(1);
            String iterable = matcher.group(2).trim();
            String indexVariable = "__rhino_for_index_" + loopCounter++;

            String replacement =
                    "for (var " + indexVariable + " = 0; " +
                            indexVariable + " < (" + iterable + ").length; " +
                            indexVariable + "++) { " +
                            "var " + variable + " = (" +
                            iterable + ")[" + indexVariable + "];";

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    static String transformForEach(String script) {
        Pattern pattern = Pattern.compile(
                "((?:[A-Za-z_$][A-Za-z0-9_$]*" +
                        "|\\([^;{}]*\\)" +
                        "|[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*" +
                        "|[A-Za-z_$][A-Za-z0-9_$]*\\([^;{}]*\\))" +
                        ")\\s*\\.\\s*forEach\\s*\\(\\s*" +
                        "\\(?\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)?\\s*=>\\s*\\{"
        );

        Matcher matcher = pattern.matcher(script);
        StringBuilder result = new StringBuilder();
        int searchStart = 0;
        int loopCounter = 0;

        while (matcher.find(searchStart)) {
            result.append(script, searchStart, matcher.start());

            String array = matcher.group(1).trim();
            String variable = matcher.group(2);
            int bodyStart = matcher.end();

            int depth = 1;
            int i = bodyStart;
            boolean inSingle = false;
            boolean inDouble = false;
            boolean escaped = false;

            while (i < script.length() && depth > 0) {
                char c = script.charAt(i);

                if (escaped) {
                    escaped = false;
                    i++;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    i++;
                    continue;
                }

                if (inSingle) {
                    if (c == '\'') inSingle = false;
                } else if (inDouble) {
                    if (c == '"') inDouble = false;
                } else {
                    if (c == '\'') inSingle = true;
                    else if (c == '"') inDouble = true;
                    else if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
                i++;
            }

            if (depth != 0) {
                result.append(script, matcher.start(), script.length());
                return result.toString();
            }

            int end = i;
            while (end < script.length() && Character.isWhitespace(script.charAt(end))) {
                end++;
            }
            if (end < script.length() && script.charAt(end) == ')') {
                end++;
                while (end < script.length() && Character.isWhitespace(script.charAt(end))) {
                    end++;
                }
                if (end < script.length() && script.charAt(end) == ';') {
                    end++;
                }
            }

            String indexVariable = "__rhino_foreach_index_" + loopCounter++;

            result.append(
                    "for (var " + indexVariable + " = 0; " +
                            indexVariable + " < (" + array + ").length; " +
                            indexVariable + "++) { " +
                            "(function(" + variable + ") {"
            );
            result.append(script, bodyStart, i - 1);
            result.append("})(" + array + "[" + indexVariable + "]); }");

            searchStart = end;
        }

        result.append(script, searchStart, script.length());
        return result.toString();
    }

    static String transformForEachExpression(String script) {
        Pattern pattern = Pattern.compile(
                "([A-Za-z_$][A-Za-z0-9_$]*)\\.forEach\\s*\\(\\s*" +
                        "\\(?\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)?\\s*=>\\s*" +
                        "([^;]+?)\\s*\\)\\s*;"
        );

        Matcher matcher = pattern.matcher(script);
        StringBuffer result = new StringBuffer();
        int loopCounter = 0;

        while (matcher.find()) {
            String array = matcher.group(1);
            String variable = matcher.group(2);
            String expression = matcher.group(3).trim();
            String indexVariable = "__rhino_foreach_index_" + loopCounter++;

            String replacement =
                    "for (var " + indexVariable + " = 0; " +
                            indexVariable + " < (" + array + ").length; " +
                            indexVariable + "++) { " +
                            "var " + variable + " = (" +
                            array + ")[" + indexVariable + "]; " +
                            expression + "; }";

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }
}