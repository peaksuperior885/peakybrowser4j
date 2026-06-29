package com.peak885.peakybrowser4j.browser.css;

import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.peak885.peakybrowser4j.browser.Node;
import com.peak885.peakybrowser4j.browser.DisplayType;
import com.peak885.peakybrowser4j.browser.css.computing.ComputedStyle;
import com.peak885.peakybrowser4j.browser.util.ColorParser;
import org.tinylog.Logger;

public class StyleEngine {

    private static final boolean DEBUG = true;

    private int ruleApplicationCount = 0;
    private int matchCount = 0;

    public void applyStyles(Node node, CascadingStyleSheet sheet) {
        if (node == null) return;

        ruleApplicationCount = 0;
        matchCount = 0;

        // === LOG STYLESHEET INFO ===
        if (DEBUG) {
            Logger.info("=== STYLESHEET ANALYSIS ===");
            Logger.info("Total rules: {}", sheet.getStyleRuleCount());

            int ruleIdx = 0;
            for (CSSStyleRule rule : sheet.getAllStyleRules()) {
                String sels = getRuleSelectorString(rule);
                int declCount = rule.getAllDeclarations().size();
                Logger.info("  Rule {}: Selectors='{}' Declarations={}",
                        ruleIdx++, sels, declCount);

                if (ruleIdx > 20) {
                    Logger.info("  ... and {} more rules", sheet.getStyleRuleCount() - 20);
                    break;
                }
            }
            Logger.info("=== END STYLESHEET ===\n");
        }

        // === DEFAULTS ===
        if (node.computedStyle.backgroundColor == null) {
            node.computedStyle.backgroundColor = new float[]{0.98f, 0.98f, 0.98f, 1f};
        }
        if (node.computedStyle.textColor == null) {
            node.computedStyle.textColor = new float[]{0.1f, 0.1f, 0.1f, 1f};
        }
        if (node.computedStyle.fontSize <= 0) {
            node.computedStyle.fontSize = 16f;
        }

        // Apply stylesheet rules
        applyStylesToNode(node, sheet, 0);

        Logger.info("Style application complete: {} rules applied, {} nodes matched",
                ruleApplicationCount, matchCount);
    }

    /**
     * Get selector string by manually building from selectors
     * Works with any version of ph-css
     */
    private String getRuleSelectorString(CSSStyleRule rule) {
        StringBuilder sb = new StringBuilder();
        for (CSSSelector sel : rule.getAllSelectors()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(sel.getAsCSSString());
        }
        return sb.toString();
    }

    private void applyStylesToNode(Node node, CascadingStyleSheet sheet, int depth) {
        if (node == null) return;

        // Log nodes with null tags
        if (node.tag == null) {
            Logger.warn("Found node with NULL tag at depth {}, type={}, text={}",
                    depth, node.type, node.text != null ? node.text.substring(0, Math.min(20, node.text.length())) : "null");
            // Still process children
            for (Node child : node.children) {
                inheritStyles(node, child);
                applyStylesToNode(child, sheet, depth + 1);
            }
            return;
        }

        String indent = "  ".repeat(Math.min(depth, 10));
        String nodeInfo = node.tag + (node.id != null ? "#" + node.id : "") +
                (node.classes.isEmpty() ? "" : "." + String.join(".", node.classes));

        if (DEBUG && depth < 5) {
            Logger.debug("{}Checking node: {}", indent, nodeInfo);
        }

        // Apply stylesheet rules
        for (CSSStyleRule rule : sheet.getAllStyleRules()) {
            if (matches(node, rule)) {
                if (DEBUG && depth < 5) {
                    Logger.debug("{}  ✓ MATCH: {}", indent, getRuleSelectorString(rule));
                }

                matchCount++;
                for (var decl : rule.getAllDeclarations()) {
                    String prop = decl.getProperty();
                    String value = decl.getExpressionAsCSSString();

                    if (DEBUG && depth < 5) {
                        Logger.debug("{}    Applying: {} = {}", indent, prop, value);
                    }

                    applyProperty(node, prop, value);
                    ruleApplicationCount++;
                }
            }
        }

        // Log final computed style for important nodes
        if (DEBUG && (node.tag.startsWith("h") || node.tag.equals("body") || node.tag.equals("a") || node.tag.equals("div"))) {
            Logger.info("{}Final style for <{}>: bg={}, color={}, fontSize={}",
                    indent, node.tag,
                    formatColor(node.computedStyle.backgroundColor),
                    formatColor(node.computedStyle.textColor),
                    node.computedStyle.fontSize
            );
        }

        // Recurse to children
        for (Node child : node.children) {
            applyStylesToNode(child, sheet, depth + 1);
        }
    }

    private String formatColor(float[] rgba) {
        if (rgba == null) return "null";
        return String.format("rgba(%.0f, %.0f, %.0f, %.2f)",
                rgba[0] * 255, rgba[1] * 255, rgba[2] * 255, rgba[3]);
    }

    private boolean matches(Node node, CSSStyleRule rule) {
        if (node == null || node.tag == null) {
            return false;
        }

        for (CSSSelector selector : rule.getAllSelectors()) {
            String sel = selector.getAsCSSString().trim().toLowerCase();

            // Tag match
            if (sel.equals(node.tag)) {
                return true;
            }

            // Class match
            if (sel.startsWith(".")) {
                String[] parts = sel.split("\\.");
                for (String part : parts) {
                    if (!part.isEmpty() && node.classes != null && node.classes.contains(part)) {
                        return true;
                    }
                }
            }

            // ID match
            if (sel.startsWith("#")) {
                String id = sel.substring(1);
                if (id != null && id.equals(node.id)) {
                    return true;
                }
            }

            // Descendant selector: ".parent h1" or "div p"
            if (sel.contains(" ")) {
                String[] parts = sel.split(" ");
                String lastPart = parts[parts.length - 1].toLowerCase().trim();
                if (!lastPart.isEmpty() && lastPart.equals(node.tag)) {
                    return true;
                }
            }

            // Child combinator: "div > p"
            if (sel.contains(">")) {
                String[] parts = sel.split(">");
                String lastPart = parts[parts.length - 1].toLowerCase().trim();
                if (!lastPart.isEmpty() && lastPart.equals(node.tag)) {
                    return true;
                }
            }

            // Sibling combinator: "h1 + p"
            if (sel.contains("+")) {
                String[] parts = sel.split("\\+");
                String lastPart = parts[parts.length - 1].toLowerCase().trim();
                if (!lastPart.isEmpty() && lastPart.equals(node.tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyProperty(Node node, String prop, String value) {
        if (prop == null || value == null) return;

        ComputedStyle style = node.computedStyle;

        switch (prop.toLowerCase()) {
            case "display" -> {
                switch (value.toLowerCase()) {
                    case "block" -> style.display = DisplayType.BLOCK;
                    case "inline" -> style.display = DisplayType.INLINE;
                    case "none" -> style.display = DisplayType.NONE;
                }
            }
            case "width" -> style.width = parsePx(value);
            case "height" -> style.height = parsePx(value);

            case "margin-top" -> style.marginTop = parsePx(value);
            case "margin-bottom" -> style.marginBottom = parsePx(value);
            case "margin-left" -> style.marginLeft = parsePx(value);
            case "margin-right" -> style.marginRight = parsePx(value);

            case "padding-top" -> style.paddingTop = parsePx(value);
            case "padding-bottom" -> style.paddingBottom = parsePx(value);
            case "padding-left" -> style.paddingLeft = parsePx(value);
            case "padding-right" -> style.paddingRight = parsePx(value);

            case "font-size" -> style.fontSize = parsePx(value);
            case "font-family" -> style.fontFamily = value.replace("\"", "").trim();

            case "color" -> {
                float[] parsed = ColorParser.parse(value);
                style.textColor = parsed;
                Logger.debug("Color property applied: {} -> {}", value, formatColor(parsed));
            }
            case "background-color" -> {
                float[] parsed = ColorParser.parse(value);
                style.backgroundColor = parsed;
                Logger.debug("Background-color property applied: {} -> {}", value, formatColor(parsed));
            }
        }
    }

    private void inheritStyles(Node parent, Node child) {

        if (child.computedStyle.textColor == null) {
            child.computedStyle.textColor = parent.computedStyle.textColor;
        }

        if (child.computedStyle.fontSize <= 0) {
            child.computedStyle.fontSize = parent.computedStyle.fontSize;
        }

        if (child.computedStyle.fontFamily == null) {
            child.computedStyle.fontFamily = parent.computedStyle.fontFamily;
        }
    }

    private float parsePx(String value) {
        if (value == null || value.equals("auto")) return -1f;
        value = value.trim().toLowerCase().replace("px", "").replace("em", "").replace("rem", "");
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return 0f;
        }
    }
}