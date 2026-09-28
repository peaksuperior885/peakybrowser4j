package com.peak885.peakybrowser4jv2.browser.js.processing;

/**
 * Shared brace/paren scanners used by the JS preprocessors.
 */
final class JsParseUtils {

    private JsParseUtils() {
    }

    static boolean isWordBoundary(String script, int index) {
        if (index < 0 || index >= script.length()) {
            return true;
        }
        return !Character.isJavaIdentifierPart(script.charAt(index));
    }

    /** Returns index of the matching ')' for the '(' at {@code openParen}, or -1. */
    static int findMatchingParen(String script, int openParen) {
        int depth = 1;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        for (int i = openParen + 1; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
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
            if (inTemplate) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '`') inTemplate = false;
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '`') { inTemplate = true; continue; }

            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Returns index of the matching '}' for the '{' at {@code openBrace}, or -1. */
    static int findMatchingBrace(String script, int openBrace) {
        int depth = 1;

        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inRegex = false;
        boolean escaped = false;

        for (int i = openBrace + 1; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
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
            if (inTemplate) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '`') inTemplate = false;
                continue;
            }
            if (inRegex) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '/') inRegex = false;
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '`') { inTemplate = true; continue; }

            if (c == '/') {
                int previous = i - 1;
                while (previous >= 0 && Character.isWhitespace(script.charAt(previous))) {
                    previous--;
                }
                boolean regexStart = previous < 0
                        || script.charAt(previous) == '='
                        || script.charAt(previous) == '('
                        || script.charAt(previous) == '['
                        || script.charAt(previous) == '{'
                        || script.charAt(previous) == ','
                        || script.charAt(previous) == ':'
                        || script.charAt(previous) == ';'
                        || script.charAt(previous) == '!'
                        || script.charAt(previous) == '?'
                        || script.charAt(previous) == '&'
                        || script.charAt(previous) == '|'
                        || script.charAt(previous) == '+'
                        || script.charAt(previous) == '-'
                        || script.charAt(previous) == '*'
                        || script.charAt(previous) == '%'
                        || script.charAt(previous) == '^'
                        || script.charAt(previous) == '~'
                        || script.charAt(previous) == '<'
                        || script.charAt(previous) == '>';
                if (regexStart) {
                    inRegex = true;
                    continue;
                }
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}