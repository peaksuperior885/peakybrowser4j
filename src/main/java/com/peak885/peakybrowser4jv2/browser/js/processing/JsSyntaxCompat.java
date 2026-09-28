package com.peak885.peakybrowser4jv2.browser.js.processing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small ES6 → ES5 syntax rewrites: default parameters, rest/spread,
 * {@code const}/{@code let}, optional chaining, nullish coalescing,
 * trailing commas, and bare {@code await}.
 */
final class JsSyntaxCompat {

    private JsSyntaxCompat() {
    }

    static String stripDefaultParameters(String script) {
        StringBuilder out = new StringBuilder(script.length());
        int i = 0;

        while (i < script.length()) {
            if (script.charAt(i) == '(') {
                int prev = i - 1;
                while (prev >= 0 && Character.isWhitespace(script.charAt(prev))) {
                    prev--;
                }

                boolean isParamList = false;
                if (prev >= 0) {
                    if (prev >= 7 && script.regionMatches(prev - 7, "function", 0, 8)
                            && (prev - 8 < 0
                            || !Character.isJavaIdentifierPart(script.charAt(prev - 8)))) {
                        isParamList = true;
                    }
                }

                if (isParamList) {
                    int close = JsParseUtils.findMatchingParen(script, i);
                    if (close > i) {
                        String params = script.substring(i + 1, close);
                        String stripped = stripDefaultsFromParamList(params);
                        out.append('(').append(stripped).append(')');
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(script.charAt(i));
            i++;
        }

        return out.toString();
    }

    private static String stripDefaultsFromParamList(String params) {
        if (params == null || params.isBlank() || params.indexOf('=') < 0) {
            return params;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        int depthParen = 0;
        int depthBrace = 0;
        int depthBracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        int paramStart = 0;

        while (i <= params.length()) {
            boolean atEnd = i == params.length();
            char c = atEnd ? ',' : params.charAt(i);

            if (!atEnd) {
                if (inSingle) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '\'') inSingle = false;
                    i++;
                    continue;
                }
                if (inDouble) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inDouble = false;
                    i++;
                    continue;
                }
                if (c == '\'') { inSingle = true; i++; continue; }
                if (c == '"') { inDouble = true; i++; continue; }
                if (c == '(') { depthParen++; i++; continue; }
                if (c == ')') { depthParen--; i++; continue; }
                if (c == '{') { depthBrace++; i++; continue; }
                if (c == '}') { depthBrace--; i++; continue; }
                if (c == '[') { depthBracket++; i++; continue; }
                if (c == ']') { depthBracket--; i++; continue; }
            }

            if ((c == ',' || atEnd)
                    && depthParen == 0 && depthBrace == 0 && depthBracket == 0
                    && !inSingle && !inDouble) {

                String param = params.substring(paramStart, i).trim();
                int eq = findTopLevelEquals(param);
                if (eq >= 0) {
                    param = param.substring(0, eq).trim();
                }
                if (!param.isEmpty()) {
                    if (result.length() > 0) result.append(',');
                    result.append(param);
                }
                paramStart = i + 1;
            }

            if (!atEnd) i++;
            else break;
        }

        return result.toString();
    }

    private static int findTopLevelEquals(String param) {
        int depthParen = 0, depthBrace = 0, depthBracket = 0;
        boolean inSingle = false, inDouble = false, escaped = false;
        for (int i = 0; i < param.length(); i++) {
            char c = param.charAt(i);
            if (inSingle) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '\'') inSingle = false;
                continue;
            }
            if (inDouble) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inDouble = false;
                continue;
            }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '(') depthParen++;
            else if (c == ')') depthParen--;
            else if (c == '{') depthBrace++;
            else if (c == '}') depthBrace--;
            else if (c == '[') depthBracket++;
            else if (c == ']') depthBracket--;
            else if (c == '=' && depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                if (i + 1 < param.length() && param.charAt(i + 1) == '=') continue;
                if (i + 1 < param.length() && param.charAt(i + 1) == '>') continue;
                if (i > 0 && param.charAt(i - 1) == '=') continue;
                if (i > 0 && param.charAt(i - 1) == '!') continue;
                return i;
            }
        }
        return -1;
    }

    static String transformRestAndSpread(String script) {
        script = script.replaceAll(
                "function\\s*\\(([^)]*)\\.\\.\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)",
                "function($1){var $2=Array.prototype.slice.call(arguments);"
        );

        Pattern restParam = Pattern.compile(
                "function\\s*\\(\\s*"
                        + "((?:[A-Za-z_$][A-Za-z0-9_$]*\\s*,\\s*)*)"
                        + "\\.\\.\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)"
        );

        Matcher m = restParam.matcher(script);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String leading = m.group(1) == null
                    ? ""
                    : m.group(1).replaceAll(",\\s*$", "");
            String restName = m.group(2);
            int leadingCount = leading.isEmpty() ? 0 : leading.split(",").length;

            String replacement =
                    "function(" + leading + "){"
                            + "var " + restName
                            + "=Array.prototype.slice.call(arguments,"
                            + leadingCount
                            + ");";

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        script = sb.toString();

        Pattern arrowRest = Pattern.compile(
                "\\(\\s*"
                        + "((?:[A-Za-z_$][A-Za-z0-9_$]*\\s*,\\s*)*)"
                        + "\\.\\.\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)\\s*=>"
        );

        m = arrowRest.matcher(script);
        sb = new StringBuffer();

        while (m.find()) {
            String leading = m.group(1) == null
                    ? ""
                    : m.group(1).replaceAll(",\\s*$", "");
            String restName = m.group(2);
            int leadingCount = leading.isEmpty() ? 0 : leading.split(",").length;

            String replacement =
                    "function(" + leading + "){"
                            + "var " + restName
                            + "=Array.prototype.slice.call(arguments,"
                            + leadingCount
                            + "); return ";

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        script = sb.toString();

        script = script.replaceAll(
                "\\[([^\\]]*),\\s*\\.\\.\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\]",
                "[$1].concat($2)"
        );
        script = script.replaceAll(
                "\\[\\s*\\.\\.\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\]",
                "$1.slice()"
        );
        script = script.replaceAll(
                "([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*\\(\\s*\\.\\.\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)",
                "$1.apply(null,$2)"
        );

        return script;
    }

    static String transformSimpleNullish(String script) {
        Pattern pattern = Pattern.compile(
                "([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)" +
                        "\\s*\\?\\?\\s*" +
                        "([^,;\\)\\]\\n]+)"
        );

        Matcher matcher = pattern.matcher(script);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(2).trim();
            String replacement =
                    "((" + left + ") != null ? " +
                            "(" + left + ") : " +
                            "(" + right + "))";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /** const/let → var, ?. → ., trailing commas, bare await. */
    static String normalizeKeywords(String script) {
        script = script.replaceAll("\\bconst\\b", "var");
        script = script.replaceAll("\\blet\\b", "var");
        script = script.replaceAll("\\?\\.", ".");
        script = script.replaceAll(",\\s*\\)", ")");
        script = script.replaceAll(",\\s*\\]", "]");
        script = script.replaceAll("\\bawait\\s+", "");
        return script;
    }
}