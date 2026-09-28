package com.peak885.peakybrowser4jv2.browser.js.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers ES6 {@code class} expressions to constructor + prototype assignments
 * that Rhino can execute.
 */
final class JsClassTransformer {

    private JsClassTransformer() {
    }

    static String transform(String script) {
        StringBuilder out = new StringBuilder();
        int pos = 0;

        while (pos < script.length()) {
            int classPos = findClassKeyword(script, pos);

            if (classPos < 0) {
                out.append(script, pos, script.length());
                break;
            }

            // Detect "new class ..." so we can strip the outer "new" when the
            // lowered form already returns a fresh instance.
            int newKeywordStart = findPrecedingNewKeyword(script, classPos);
            boolean directlyInstantiated = newKeywordStart >= 0;

            int appendFrom = pos;
            if (directlyInstantiated && newKeywordStart >= pos) {
                out.append(script, pos, newKeywordStart);
                appendFrom = classPos;
            } else {
                out.append(script, pos, classPos);
            }

            int cursor = classPos + 5;

            while (cursor < script.length()
                    && Character.isWhitespace(script.charAt(cursor))) {
                cursor++;
            }

            String className = null;
            int nameStart = cursor;

            if (cursor < script.length()
                    && Character.isJavaIdentifierStart(script.charAt(cursor))) {

                cursor++;
                while (cursor < script.length()
                        && Character.isJavaIdentifierPart(script.charAt(cursor))) {
                    cursor++;
                }

                String candidate = script.substring(nameStart, cursor);

                // Anonymous: "class extends Super { ... }" must not treat
                // the keyword "extends" as the class name.
                if (!"extends".equals(candidate)) {
                    className = candidate;
                } else {
                    cursor = nameStart;
                }
            }

            while (cursor < script.length()
                    && Character.isWhitespace(script.charAt(cursor))) {
                cursor++;
            }

            String superClass = null;

            if (script.startsWith("extends", cursor)
                    && JsParseUtils.isWordBoundary(script, cursor - 1)
                    && JsParseUtils.isWordBoundary(script, cursor + 7)) {

                cursor += 7;

                while (cursor < script.length()
                        && Character.isWhitespace(script.charAt(cursor))) {
                    cursor++;
                }

                int superStart = cursor;
                while (cursor < script.length()) {
                    char c = script.charAt(cursor);
                    if (c == '{' || Character.isWhitespace(c)) {
                        break;
                    }
                    cursor++;
                }

                superClass = script.substring(superStart, cursor).trim();

                while (cursor < script.length()
                        && Character.isWhitespace(script.charAt(cursor))) {
                    cursor++;
                }
            }

            if (cursor >= script.length() || script.charAt(cursor) != '{') {
                if (directlyInstantiated && newKeywordStart >= 0) {
                    out.append(script, newKeywordStart, classPos);
                }
                out.append("class");
                pos = classPos + 5;
                continue;
            }

            int bodyEnd = JsParseUtils.findMatchingBrace(script, cursor);

            if (bodyEnd < 0) {
                if (directlyInstantiated && newKeywordStart >= 0
                        && appendFrom == classPos) {
                    out.append(script, newKeywordStart, classPos);
                }
                out.append(script, classPos, script.length());
                break;
            }

            String body = script.substring(cursor + 1, bodyEnd);
            boolean anonymous = className == null;
            String generatedName = anonymous ? "__RhinoClass" + classPos : className;

            String replacement = directlyInstantiated
                    ? lowerAnonymousInstantiatedClass(generatedName, superClass, body)
                    : lowerClass(generatedName, superClass, body);

            out.append(replacement);
            pos = bodyEnd + 1;
        }

        return out.toString();
    }

    private static String lowerClass(String className, String superClass, String body) {
        StringBuilder result = new StringBuilder();
        result.append("(function(){");

        MethodPart constructor = extractMethod(body, "constructor");

        if (constructor != null) {
            result.append("function ")
                    .append(className)
                    .append("(")
                    .append(constructor.parameters)
                    .append("){");

            String constructorBody = constructor.body;
            if (superClass != null) {
                constructorBody = transformSuperCalls(constructorBody, superClass);
            }
            result.append(constructorBody);
            result.append("}");
        } else {
            result.append("function ").append(className).append("(){");
            if (superClass != null) {
                result.append(superClass).append(".apply(this,arguments);");
            }
            result.append("}");
        }

        if (superClass != null) {
            result.append(className)
                    .append(".prototype=Object.create(")
                    .append(superClass)
                    .append(".prototype);");
            result.append(className)
                    .append(".prototype.constructor=")
                    .append(className)
                    .append(";");
        }

        for (MethodPart method : extractMethods(body)) {
            if ("constructor".equals(method.name)) {
                continue;
            }
            String methodBody = method.body;
            if (superClass != null) {
                methodBody = transformSuperCalls(methodBody, superClass);
            }
            result.append(className)
                    .append(".prototype.")
                    .append(method.name)
                    .append("=function(")
                    .append(method.parameters)
                    .append("){")
                    .append(methodBody)
                    .append("};");
        }

        result.append("return ").append(className).append(";");
        result.append("})()");
        return result.toString();
    }

    private static String lowerAnonymousInstantiatedClass(
            String className,
            String superClass,
            String body
    ) {
        StringBuilder result = new StringBuilder();
        result.append("(function(){");

        MethodPart constructor = extractMethod(body, "constructor");

        if (constructor != null) {
            result.append("function ")
                    .append(className)
                    .append("(")
                    .append(constructor.parameters)
                    .append("){");

            String constructorBody = constructor.body;
            if (superClass != null) {
                constructorBody = transformSuperCalls(constructorBody, superClass);
            }
            result.append(constructorBody);
            result.append("}");
        } else {
            result.append("function ").append(className).append("(){");
            if (superClass != null) {
                result.append(superClass).append(".apply(this,arguments);");
            }
            result.append("}");
        }

        if (superClass != null) {
            result.append(className)
                    .append(".prototype=Object.create(")
                    .append(superClass)
                    .append(".prototype);");
            result.append(className)
                    .append(".prototype.constructor=")
                    .append(className)
                    .append(";");
        }

        for (MethodPart method : extractMethods(body)) {
            if ("constructor".equals(method.name)) {
                continue;
            }
            String methodBody = method.body;
            if (superClass != null) {
                methodBody = transformSuperCalls(methodBody, superClass);
            }
            result.append(className)
                    .append(".prototype.")
                    .append(method.name)
                    .append("=function(")
                    .append(method.parameters)
                    .append("){")
                    .append(methodBody)
                    .append("};");
        }

        result.append("return new ").append(className).append("();");
        result.append("})()");
        return result.toString();
    }

    private static String transformSuperCalls(String body, String superClass) {
        body = body.replaceAll(
                "\\bsuper\\s*\\(([^)]*)\\)",
                Matcher.quoteReplacement(superClass) + ".call(this,$1)"
        );
        body = body.replaceAll(
                "\\bsuper\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(",
                Matcher.quoteReplacement(superClass) + ".prototype.$1.call(this,"
        );
        return body;
    }

    private static final class MethodPart {
        final String name;
        final String parameters;
        final String body;

        MethodPart(String name, String parameters, String body) {
            this.name = name;
            this.parameters = parameters;
            this.body = body;
        }
    }

    private static MethodPart extractMethod(String body, String wantedName) {
        Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(wantedName) + "\\s*\\(([^)]*)\\)\\s*\\{"
        );
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        int openBrace = matcher.end() - 1;
        int closeBrace = JsParseUtils.findMatchingBrace(body, openBrace);
        if (closeBrace < 0) {
            return null;
        }
        return new MethodPart(
                wantedName,
                matcher.group(1).trim(),
                body.substring(openBrace + 1, closeBrace)
        );
    }

    /**
     * Reserved words that look like {@code name(...)} but are control-flow /
     * declarations, not class methods.
     */
    private static final Set<String> NON_METHOD_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case",
            "try", "catch", "finally", "with", "function", "return",
            "throw", "new", "typeof", "instanceof", "void", "delete",
            "var", "let", "const", "class", "extends", "static",
            "get", "set", "async", "await", "yield", "import", "export"
    );

    /**
     * Extract class methods by scanning only at brace-depth 0 of the class
     * body. Nested {@code for(...)} / {@code catch(...)} / etc. are ignored.
     */
    private static List<MethodPart> extractMethods(String body) {
        List<MethodPart> methods = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return methods;
        }

        int i = 0;
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inRegex = false;
        boolean escaped = false;

        while (i < body.length()) {
            char c = body.charAt(i);
            char next = i + 1 < body.length() ? body.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
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
            if (inTemplate) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '`') inTemplate = false;
                i++;
                continue;
            }
            if (inRegex) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '/') inRegex = false;
                i++;
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i += 2;
                continue;
            }
            if (c == '\'') { inSingle = true; i++; continue; }
            if (c == '"') { inDouble = true; i++; continue; }
            if (c == '`') { inTemplate = true; i++; continue; }

            if (c == '/') {
                int previous = i - 1;
                while (previous >= 0 && Character.isWhitespace(body.charAt(previous))) {
                    previous--;
                }
                boolean regexStart = previous < 0
                        || "=([{,;:!&|+-*%^~<>".indexOf(body.charAt(previous)) >= 0;
                if (regexStart) {
                    inRegex = true;
                    i++;
                    continue;
                }
            }

            if (c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                i++;
                continue;
            }

            if (depth == 0 && Character.isJavaIdentifierStart(c)) {
                int nameStart = i;
                i++;
                while (i < body.length()
                        && Character.isJavaIdentifierPart(body.charAt(i))) {
                    i++;
                }
                String name = body.substring(nameStart, i);

                int afterName = i;
                while (afterName < body.length()
                        && Character.isWhitespace(body.charAt(afterName))) {
                    afterName++;
                }

                if (afterName < body.length()
                        && body.charAt(afterName) == '('
                        && !NON_METHOD_KEYWORDS.contains(name)) {

                    int paramStart = afterName + 1;
                    int paramEnd = JsParseUtils.findMatchingParen(body, afterName);
                    if (paramEnd < 0) {
                        i = afterName + 1;
                        continue;
                    }

                    int afterParams = paramEnd + 1;
                    while (afterParams < body.length()
                            && Character.isWhitespace(body.charAt(afterParams))) {
                        afterParams++;
                    }

                    if (afterParams < body.length()
                            && body.charAt(afterParams) == '{') {

                        int closeBrace = JsParseUtils.findMatchingBrace(body, afterParams);
                        if (closeBrace < 0) {
                            break;
                        }

                        String params = body.substring(paramStart, paramEnd).trim();
                        String methodBody = body.substring(afterParams + 1, closeBrace);
                        methods.add(new MethodPart(name, params, methodBody));
                        i = closeBrace + 1;
                        continue;
                    }
                }

                i = afterName;
                continue;
            }

            i++;
        }

        return methods;
    }

    private static int findClassKeyword(String script, int start) {
        for (int i = start; i <= script.length() - 5; i++) {
            if (!script.regionMatches(i, "class", 0, 5)) {
                continue;
            }
            boolean before = i == 0
                    || !Character.isJavaIdentifierPart(script.charAt(i - 1));
            boolean after = i + 5 >= script.length()
                    || !Character.isJavaIdentifierPart(script.charAt(i + 5));
            if (before && after) {
                return i;
            }
        }
        return -1;
    }

    /**
     * If {@code classPos} is preceded by a {@code new} keyword (with only
     * whitespace in between), returns the index of that {@code new};
     * otherwise returns -1.
     */
    private static int findPrecedingNewKeyword(String script, int classPos) {
        int cursor = classPos - 1;
        while (cursor >= 0 && Character.isWhitespace(script.charAt(cursor))) {
            cursor--;
        }

        String keyword = "new";
        int start = cursor - keyword.length() + 1;
        if (start < 0) {
            return -1;
        }
        if (!script.regionMatches(start, keyword, 0, keyword.length())) {
            return -1;
        }
        if (start > 0 && Character.isJavaIdentifierPart(script.charAt(start - 1))) {
            return -1;
        }
        return start;
    }
}