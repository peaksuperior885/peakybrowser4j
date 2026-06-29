package com.peak885.peakybrowser4j.browser.css;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class CssSanitizer {

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("@import\\s+[^;]+;");
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("@namespace\\s+[^;]+;");

    // Properties that use vendor prefixes (safe to remove for standard rendering)
    private static final Pattern VENDOR_PREFIX_PATTERN = Pattern.compile("-(?:webkit|moz|ms|o|khtml)-[a-z-]+");

    // Dangerous directives
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(?:behavior|expression|javascript:|vbscript:|-moz-binding|binding|\\\\[0-9a-f])\\s*[:=]",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Sanitizes CSS for a custom renderer, removing vendor prefixes
     * and keeping only standard CSS properties.
     */
    public String sanitize(String css) {
        if (css == null || css.isBlank()) return "";

        // 1. Remove comments
        css = COMMENT_PATTERN.matcher(css).replaceAll(" ");

        // 2. Remove dangerous directives
        css = IMPORT_PATTERN.matcher(css).replaceAll("");
        css = NAMESPACE_PATTERN.matcher(css).replaceAll("");

        // 3. Remove dangerous property values
        css = removeDangerousProperties(css);

        // 4. Remove vendor-prefixed properties (custom renderer can't use them)
        css = removeVendorPrefixes(css);

        // 5. Normalize whitespace
        css = css.replaceAll("\\s+", " ");
        css = css.replaceAll("\\s*([{};:,>+~])\\s*", "$1");
        css = css.replaceAll("}\\s*", "}\n");

        System.out.println("Sanitized CSS: " + css.length() + " chars");
        return css.trim();
    }

    /**
     * Removes vendor-prefixed properties (-webkit-, -moz-, -ms-, etc).
     * Custom renderers can't use these anyway.
     */
    private String removeVendorPrefixes(String css) {
        StringBuilder result = new StringBuilder();
        String[] rules = css.split("\\}");

        for (String rule : rules) {
            if (!rule.contains("{")) continue;

            String[] parts = rule.split("\\{", 2);
            String selector = parts[0];
            String properties = parts.length > 1 ? parts[1] : "";

            // Remove vendor-prefixed properties from the rule body
            String[] declarations = properties.split(";");
            StringBuilder cleanedProps = new StringBuilder();

            for (String decl : declarations) {
                String trimmed = decl.trim();
                if (trimmed.isEmpty()) continue;

                // Skip vendor-prefixed properties
                if (VENDOR_PREFIX_PATTERN.matcher(trimmed).find()) {
                    System.out.println("Removed vendor prefix: " + trimmed.substring(0, Math.min(50, trimmed.length())));
                    continue;
                }

                cleanedProps.append(trimmed).append(";");
            }

            if (cleanedProps.length() > 0) {
                result.append(selector).append("{").append(cleanedProps).append("}");
            }
        }

        return result.toString();
    }

    /**
     * Removes dangerous property values.
     */
    private String removeDangerousProperties(String css) {
        Matcher matcher = DANGEROUS_PATTERN.matcher(css);
        return matcher.replaceAll("");
    }

    /**
     * Validates CSS structure (matching braces/brackets).
     */
    public boolean isValidCss(String css) {
        if (css == null || css.isBlank()) return true;

        int braces = 0, brackets = 0, parens = 0;

        for (char c : css.toCharArray()) {
            switch (c) {
                case '{': braces++; break;
                case '}': braces--; break;
                case '[': brackets++; break;
                case ']': brackets--; break;
                case '(': parens++; break;
                case ')': parens--; break;
            }

            if (braces < 0 || brackets < 0 || parens < 0) {
                System.err.println("CSS validation failed: mismatched brackets");
                return false;
            }
        }

        if (braces != 0 || brackets != 0 || parens != 0) {
            System.err.println("CSS validation failed: unclosed brackets");
            return false;
        }

        return true;
    }

    /**
     * Sanitizes and validates in one step.
     */
    public String sanitizeAndValidate(String css) {
        String sanitized = sanitize(css);
        if (!isValidCss(sanitized)) {
            System.err.println("CSS validation failed after sanitization");
            return "";
        }
        return sanitized;
    }

    /**
     * Dumps CSS to file for debugging.
     */
    public void dumpCssToFile(String cssContent, String filename) {
        String userHome = System.getProperty("user.home");
        String path = userHome + "/Downloads/" + filename;

        try {
            Files.write(Paths.get(path), cssContent.getBytes());
            System.out.println("CSS dumped to: " + path);
        } catch (IOException e) {
            System.err.println("Failed to dump CSS: " + e.getMessage());
            e.printStackTrace();
        }
    }
}