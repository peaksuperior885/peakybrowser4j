package com.peak885.peakybrowser4jv2.browser.render.style;

import com.helger.css.decl.CSSDeclaration;
import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.LoggingCSSParseErrorHandler;
import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.tinylog.Logger;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the {@link ComputedStyle} for an element by matching CSS rules
 * against it.
 * <p>
 * The selector engine understands descendant ({@code a b}), child
 * ({@code a > b}), adjacent-sibling ({@code a + b}) and general-sibling
 * ({@code a ~ b}) combinators, id/class/tag/attribute selectors, and a
 * useful subset of pseudo-classes. Matching rules are applied in cascade
 * order: user-agent rules first, then author rules, each internally ordered
 * by specificity (and document order as a tiebreaker) rather than by the
 * order they happened to be encountered while scanning the stylesheet.
 */
public final class StyleResolver {

    private volatile List<CSSStyleRule> uaRules = List.of();
    private volatile List<CSSStyleRule> authorRules = List.of();
    private volatile Map<String, String> customProperties = Map.of();

    /** Live interaction targets for :hover / :active / :focus matching. */
    private volatile Element hoveredElement;
    private volatile Element activeElement;
    private volatile Element focusedElement;

    /**
     * Cache of selector text -> parsed combinator chain, so repeated
     * elements sharing the same stylesheet don't re-parse selector strings.
     */
    private final Map<String, List<String>> selectorTokenCache = new ConcurrentHashMap<>();

    /**
     * Cache of compound-selector text (e.g. {@code div.header#nav}) -> parsed
     * {@link Compound}.
     */
    private final Map<String, Compound> compoundCache = new ConcurrentHashMap<>();
    public StyleResolver() {
        loadUserAgentStyles();
    }

    public void setHoveredElement(Element element) {
        this.hoveredElement = element;
    }

    public void setActiveElement(Element element) {
        this.activeElement = element;
    }

    public void setFocusedElement(Element element) {
        this.focusedElement = element;
    }

    public Element hoveredElement() {
        return hoveredElement;
    }

    public Element activeElement() {
        return activeElement;
    }

    public Element focusedElement() {
        return focusedElement;
    }

    /** True when the given element is the hovered target or an ancestor of it. */
    private boolean isHovered(Element el) {
        return isSelfOrAncestorOf(el, hoveredElement);
    }

    private boolean isActive(Element el) {
        return isSelfOrAncestorOf(el, activeElement);
    }

    private boolean isFocused(Element el) {
        return el != null && el == focusedElement;
    }

    private static boolean isSelfOrAncestorOf(Element candidate, Element target) {
        if (candidate == null || target == null) {
            return false;
        }
        for (Element e = target; e != null; e = e.parent()) {
            if (e == candidate) {
                return true;
            }
        }
        return false;
    }

    private void loadUserAgentStyles() {
        String css = loadCssFromResource("/user-agent.css");

        if (css.isBlank()) {
            uaRules = List.of();
            return;
        }

        try {
            CascadingStyleSheet sheet =
                    CSSReader.readFromString(css);

            if (sheet != null) {
                uaRules = List.copyOf(
                        sheet.getAllStyleRules()
                );
            } else {
                uaRules = List.of();
            }

        } catch (Exception e) {
            Logger.error(e);
            uaRules = List.of();
        }
    }

    public void setAuthorStyles(String cssText) {

        if (cssText == null || cssText.isBlank()) {
            authorRules = List.of();
            customProperties = Map.of();

            selectorTokenCache.clear();
            compoundCache.clear();
            return;
        }

        try {
            /*
             * Real-world CSS (this includes Google's own pages - see the
             * leftover "@if (RTL_LANG){...}" template syntax and doubled-ID
             * selectors like "#gb#gb ...") is often technically invalid.
             *
             * Real browsers are required by spec to skip an unrecognized
             * at-rule's block and keep parsing everything after it, rather
             * than aborting the whole stylesheet. ph-css's strict default
             * mode does not extend that same courtesy, so without browser
             * compliant mode a single malformed construct can silently
             * zero out an otherwise perfectly good stylesheet.
             */
            CSSReaderSettings settings =
                    new CSSReaderSettings()
                            .setBrowserCompliantMode(true)
                            .setCustomErrorHandler(
                                    new LoggingCSSParseErrorHandler()
                            );

            CascadingStyleSheet sheet =
                    CSSReader.readFromStringReader(cssText, settings);

            if (sheet == null) {
                Logger.warn(
                        "[CSS] author stylesheet failed to parse entirely ({} chars)",
                        cssText.length()
                );

                authorRules = List.of();
                customProperties = Map.of();
            } else {
                List<CSSStyleRule> newRules =
                        List.copyOf(sheet.getAllStyleRules());

                Map<String, String> newCustomProperties =
                        harvestCustomProperties(sheet);

                Logger.info(
                        "[CSS] author stylesheet parsed: {} rule(s) from {} chars",
                        newRules.size(),
                        cssText.length()
                );

                authorRules = newRules;
                customProperties = newCustomProperties;
            }

            selectorTokenCache.clear();
            compoundCache.clear();

        } catch (Exception e) {
            Logger.error(e);
        }
    }

    private Map<String, String> harvestCustomProperties(
            CascadingStyleSheet sheet
    ) {
        Map<String, String> properties =
                new HashMap<>();

        for (CSSStyleRule rule : sheet.getAllStyleRules()) {
            for (CSSSelector selector : rule.getAllSelectors()) {

                String sel =
                        selector.getAsCSSString()
                                .trim()
                                .toLowerCase();

                if (sel.contains(":root")
                        || sel.equals("html")) {

                    for (CSSDeclaration decl :
                            rule.getAllDeclarations()) {

                        String prop =
                                decl.getProperty();

                        if (prop != null
                                && prop.startsWith("--")) {

                            properties.put(
                                    prop,
                                    decl.getExpressionAsCSSString()
                            );
                        }
                    }
                }
            }
        }

        Logger.info(
                "[CSS] custom properties loaded: {}",
                properties
        );

        return Map.copyOf(properties);
    }

    public void loadAuthorStylesFromResource(String resourcePath) {
        String css = loadCssFromResource(resourcePath);
        setAuthorStyles(css);
    }

    public static String loadCssFromResource(String resourcePath) {
        try (InputStream in = StyleResolver.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("Resource stylesheet not found: " + resourcePath);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public ComputedStyle resolve(Element element, ComputedStyle parent) {
        ComputedStyle style = new ComputedStyle();

        if (parent != null) {
            style.set("color", parent.get("color", "#000000"));
            style.set("font-size", parent.get("font-size", "16px"));
            style.set("font-family", parent.get("font-family", "sans-serif"));
            style.set("font-weight", parent.get("font-weight", "normal"));
            style.set("font-style", parent.get("font-style", "normal"));
            style.set("line-height", parent.get("line-height", "1.2"));
            // text-align is inherited (CSS 2.1 §14.2 / CSS Cascade)
            style.set("text-align", parent.get("text-align", "left"));
        }

        applyMatchingRules(element, uaRules, style);
        applyMatchingRules(element, authorRules, style);

        String inline = element.attr("style");
        if (!inline.isBlank()) {
            applyInline(inline, style);
        }

        // HTML align attribute (presentational hint) — used heavily by
        // Google basic HTML (gbv=1) and other legacy pages.
        applyAlignAttribute(element, style);

        applyTagDefaults(element.tagName(), style);

        return style;
    }

    /**
     * Maps the legacy HTML {@code align} attribute to CSS {@code text-align}
     * (or margin auto for block-level horizontal centering on tables).
     */
    private void applyAlignAttribute(Element element, ComputedStyle style) {
        String align = element.attr("align");
        if (align == null || align.isBlank()) {
            return;
        }

        align = align.trim().toLowerCase();

        switch (align) {
            case "center" -> {
                // Prefer text-align so inline content centers. For tables
                // that become display:block this still centers their text;
                // real table centering would need margin:auto support.
                if (!style.has("text-align")
                        || "left".equalsIgnoreCase(style.get("text-align"))) {
                    style.set("text-align", "center");
                }
            }
            case "left", "start" -> {
                if (!style.has("text-align")) {
                    style.set("text-align", "left");
                }
            }
            case "right", "end" -> {
                if (!style.has("text-align")) {
                    style.set("text-align", "right");
                }
            }
            case "justify" -> {
                if (!style.has("text-align")) {
                    style.set("text-align", "justify");
                }
            }
            default -> { /* ignore unknown values */ }
        }
    }

    /**
     * Finds every declaration block whose selector matches {@code element},
     * then applies them in CSS cascade order: lowest specificity first,
     * document order breaking ties, so that a later/more-specific rule
     * correctly overrides an earlier/less-specific one regardless of where
     * either happened to appear while the stylesheet was being scanned.
     */
    private void applyMatchingRules(Element element, List<CSSStyleRule> rules, ComputedStyle style) {

        List<MatchedRule> matched = new ArrayList<>();

        int docOrder = 0;

        for (CSSStyleRule rule : rules) {
            for (CSSSelector selector : rule.getAllSelectors()) {

                String sel = selector.getAsCSSString().trim();

                if (sel.isEmpty()) {
                    docOrder++;
                    continue;
                }

                List<String> tokens = selectorTokenCache.computeIfAbsent(sel, this::tokenizeSelector);

                if (matchesChain(element, tokens)) {
                    matched.add(new MatchedRule(specificity(tokens), docOrder, rule));
                }

                docOrder++;
            }
        }

        matched.sort(
                Comparator
                        .comparingInt((MatchedRule m) -> m.specificity())
                        .thenComparingInt(MatchedRule::docOrder)
        );

        for (MatchedRule m : matched) {
            for (CSSDeclaration decl : m.rule().getAllDeclarations()) {
                String prop = decl.getProperty();
                if (prop == null) continue;
                style.set(prop, resolveVars(decl.getExpressionAsCSSString()));
            }
        }
    }

    private record MatchedRule(int specificity, int docOrder, CSSStyleRule rule) {
    }

    /*
     * ---------------------------------------------------------------------
     * Selector tokenizing
     * ---------------------------------------------------------------------
     */

    private static final Pattern COMBINATOR_SPACING =
            Pattern.compile("\\s*([>+~])\\s*");

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+");

    /**
     * Splits a selector into a flat list alternating compound selectors and
     * explicit combinators, e.g. {@code "div.header > span.logo"} becomes
     * {@code ["div.header", ">", "span.logo"]}, and {@code "div.header
     * span.logo"} (an implicit descendant combinator) becomes
     * {@code ["div.header", "span.logo"]}.
     */
    private List<String> tokenizeSelector(String selector) {
        String normalized =
                COMBINATOR_SPACING.matcher(selector.trim())
                        .replaceAll(" $1 ");

        normalized = WHITESPACE.matcher(normalized.trim()).replaceAll(" ");

        if (normalized.isEmpty()) {
            return List.of();
        }

        return Arrays.asList(normalized.split(" "));
    }

    /**
     * Matches a selector's compound/combinator chain against an element,
     * walking up the ancestor/sibling chain as required. The last token in
     * the list is the "subject" compound that must match {@code element}
     * itself.
     */
    private boolean matchesChain(Element element, List<String> tokens) {
        if (tokens.isEmpty()) return false;

        int idx = tokens.size() - 1;

        if (!matchesCompound(element, tokens.get(idx))) {
            return false;
        }

        idx--;

        Element current = element;

        while (idx >= 0) {
            String token = tokens.get(idx);

            switch (token) {
                case ">" -> {
                    idx--;
                    if (idx < 0) return false;
                    Element parent = current.parent();
                    if (parent == null || !matchesCompound(parent, tokens.get(idx))) {
                        return false;
                    }
                    current = parent;
                    idx--;
                }
                case "+" -> {
                    idx--;
                    if (idx < 0) return false;
                    Element sibling = current.previousElementSibling();
                    if (sibling == null || !matchesCompound(sibling, tokens.get(idx))) {
                        return false;
                    }
                    current = sibling;
                    idx--;
                }
                case "~" -> {
                    idx--;
                    if (idx < 0) return false;
                    String compound = tokens.get(idx);
                    Element found = null;
                    for (Element sib = current.previousElementSibling(); sib != null; sib = sib.previousElementSibling()) {
                        if (matchesCompound(sib, compound)) {
                            found = sib;
                            break;
                        }
                    }
                    if (found == null) return false;
                    current = found;
                    idx--;
                }
                default -> {
                    // Implicit descendant combinator: some ancestor must match.
                    Element found = null;
                    for (Element anc = current.parent(); anc != null; anc = anc.parent()) {
                        if (matchesCompound(anc, token)) {
                            found = anc;
                            break;
                        }
                    }
                    if (found == null) return false;
                    current = found;
                    idx--;
                }
            }
        }

        return true;
    }

    /*
     * ---------------------------------------------------------------------
     * Compound selector parsing (tag#id.class[attr]:pseudo)
     * ---------------------------------------------------------------------
     */

    private static final class Compound {
        String tag;
        String id;
        final List<String> classes = new ArrayList<>();
        final List<AttrSelector> attrs = new ArrayList<>();
        final List<Pseudo> pseudos = new ArrayList<>();
    }

    private record AttrSelector(String name, String op, String value) {
    }

    private record Pseudo(String name, String arg) {
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private Compound parseCompound(String compound) {
        Compound c = new Compound();

        int i = 0;
        int n = compound.length();
        StringBuilder tagBuf = new StringBuilder();

        while (i < n) {
            char ch = compound.charAt(i);

            if (ch == '#') {
                int j = i + 1;
                while (j < n && isNameChar(compound.charAt(j))) j++;
                c.id = compound.substring(i + 1, j);
                i = j;

            } else if (ch == '.') {
                int j = i + 1;
                while (j < n && isNameChar(compound.charAt(j))) j++;
                if (j > i + 1) {
                    c.classes.add(compound.substring(i + 1, j));
                }
                i = j;

            } else if (ch == '[') {
                int j = compound.indexOf(']', i);
                if (j < 0) j = n; else {
                    c.attrs.add(parseAttr(compound.substring(i + 1, j)));
                }
                i = j + 1;

            } else if (ch == ':') {
                int j = i + 1;
                // ::before / ::after etc. - treat like a single-colon pseudo.
                if (j < n && compound.charAt(j) == ':') j++;
                int nameStart = j;
                while (j < n && isNameChar(compound.charAt(j))) j++;
                String name = compound.substring(nameStart, j).toLowerCase();
                String arg = null;
                if (j < n && compound.charAt(j) == '(') {
                    int depth = 1;
                    int k = j + 1;
                    int argStart = k;
                    while (k < n && depth > 0) {
                        char ck = compound.charAt(k);
                        if (ck == '(') depth++;
                        else if (ck == ')') depth--;
                        k++;
                    }
                    arg = compound.substring(argStart, Math.max(argStart, k - 1)).trim();
                    j = k;
                }
                if (!name.isEmpty()) {
                    c.pseudos.add(new Pseudo(name, arg));
                }
                i = j;

            } else if (isNameChar(ch) || ch == '*') {
                int j = i;
                while (j < n && (isNameChar(compound.charAt(j)) || compound.charAt(j) == '*')) j++;
                tagBuf.append(compound, i, j);
                i = j;

            } else {
                i++;
            }
        }

        String tag = tagBuf.toString();
        if (!tag.isEmpty() && !tag.equals("*")) {
            c.tag = tag;
        }

        return c;
    }

    private static final Pattern ATTR_PATTERN =
            Pattern.compile("([\\w-]+)\\s*([~^$*|]?=)?\\s*(.*)");

    private AttrSelector parseAttr(String inner) {
        inner = inner.trim();

        Matcher m = ATTR_PATTERN.matcher(inner);

        if (!m.matches()) {
            return new AttrSelector(inner, "", null);
        }

        String name = m.group(1);
        String op = m.group(2) == null ? "" : m.group(2);
        String value = m.group(3);

        if (value != null) {
            value = value.trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty()) value = null;
        }

        return new AttrSelector(name, op, value);
    }

    private boolean matchesCompound(Element el, String compoundToken) {
        if (compoundToken.isEmpty() || compoundToken.equals("*")) {
            return true;
        }

        Compound c = compoundCache.computeIfAbsent(compoundToken, this::parseCompound);

        if (c.tag != null && !el.tagName().equalsIgnoreCase(c.tag)) return false;
        if (c.id != null && !c.id.equals(el.id())) return false;

        for (String cls : c.classes) {
            if (!el.hasClass(cls)) return false;
        }

        for (AttrSelector a : c.attrs) {
            if (!matchesAttr(el, a)) return false;
        }

        for (Pseudo p : c.pseudos) {
            if (!matchesPseudo(el, p)) return false;
        }

        return true;
    }

    private boolean matchesAttr(Element el, AttrSelector a) {
        if (!el.hasAttr(a.name())) return false;
        if (a.value() == null) return true;

        String actual = el.attr(a.name());

        return switch (a.op()) {
            case "=" -> actual.equals(a.value());
            case "^=" -> actual.startsWith(a.value());
            case "$=" -> actual.endsWith(a.value());
            case "*=" -> actual.contains(a.value());
            case "~=" -> Arrays.asList(actual.split("\\s+")).contains(a.value());
            case "|=" -> actual.equals(a.value()) || actual.startsWith(a.value() + "-");
            default -> true;
        };
    }

    /**
     * Interactive pseudo-classes match against the live interaction state
     * set via {@link #setHoveredElement} / {@link #setActiveElement} /
     * {@link #setFocusedElement}. Structural pseudo-classes are matched
     * for real; anything unrecognized falls back to matching (better to
     * over-apply a rare selector than to lose common styling entirely).
     */
    private boolean matchesPseudo(Element el, Pseudo p) {
        return switch (p.name()) {
            case "hover" -> isHovered(el);
            case "active" -> isActive(el);
            case "focus", "focus-visible" -> isFocused(el);
            case "focus-within" -> isSelfOrAncestorOf(el, focusedElement);
            case "visited", "target" -> false;

            case "link" -> "a".equalsIgnoreCase(el.tagName()) && el.hasAttr("href");

            case "first-child" -> el.parent() != null && el.elementSiblingIndex() == 0;

            case "last-child" -> {
                Element parent = el.parent();
                yield parent != null
                        && el.elementSiblingIndex() == parent.children().size() - 1;
            }

            case "only-child" -> {
                Element parent = el.parent();
                yield parent != null && parent.children().size() == 1;
            }

            case "first-of-type" -> !hasEarlierSiblingOfSameType(el);

            case "empty" -> el.childNodeSize() == 0;

            case "not" -> p.arg() == null || !matchesCompound(el, p.arg().trim());

            case "nth-child" -> matchesNth(el, p.arg());

            default -> true;
        };
    }

    private boolean hasEarlierSiblingOfSameType(Element el) {
        String tag = el.tagName();
        for (Element sib = el.previousElementSibling(); sib != null; sib = sib.previousElementSibling()) {
            if (sib.tagName().equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    private boolean matchesNth(Element el, String arg) {
        if (arg == null) return true;

        int position = el.elementSiblingIndex() + 1;
        String a = arg.trim().toLowerCase();

        if (a.equals("odd")) return position % 2 == 1;
        if (a.equals("even")) return position % 2 == 0;

        try {
            return position == Integer.parseInt(a);
        } catch (NumberFormatException e) {
            // An+B formulas aren't evaluated - don't let an unsupported
            // form silently discard styling that would otherwise apply.
            return true;
        }
    }

    /*
     * ---------------------------------------------------------------------
     * Specificity: (id count, class/attr/pseudo count, type count), packed
     * into a single comparable int since realistic selectors never approach
     * these per-band limits.
     * ---------------------------------------------------------------------
     */

    private int specificity(List<String> tokens) {
        int ids = 0;
        int classes = 0;
        int types = 0;

        for (String token : tokens) {
            if (token.equals(">") || token.equals("+") || token.equals("~")) continue;

            Compound c = compoundCache.computeIfAbsent(token, this::parseCompound);

            if (c.id != null) ids++;
            classes += c.classes.size() + c.attrs.size() + c.pseudos.size();
            if (c.tag != null) types++;
        }

        return (ids * 1_000_000) + (classes * 1_000) + types;
    }

    private void applyInline(String inline, ComputedStyle style) {
        for (String part : inline.split(";")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2) {
                style.set(kv[0].trim(), resolveVars(kv[1].trim()));
            }
        }
    }

    private void applyTagDefaults(String tag, ComputedStyle style) {
        switch (tag.toLowerCase()) {
            case "body" -> {
                if (style.get("display").isBlank()) style.set("display", "block");
                if (style.get("margin").isBlank()) {
                    style.set("margin-top", "8px");
                    style.set("margin-right", "8px");
                    style.set("margin-bottom", "8px");
                    style.set("margin-left", "8px");
                }
            }
            case "p", "div", "section", "article", "header", "footer",
                 "nav", "main", "ul", "ol", "form", "fieldset" -> {
                if (style.get("display").isBlank()) style.set("display", "block");
            }
            case "li" -> {
                if (style.get("display").isBlank()) style.set("display", "list-item");
            }
            case "h1" -> headingDefaults(style, "2em", "0.67em");
            case "h2" -> headingDefaults(style, "1.5em", "0.83em");
            case "h3" -> headingDefaults(style, "1.17em", "1em");
            case "h4" -> headingDefaults(style, "1em", "1.33em");
            case "h5" -> headingDefaults(style, "0.83em", "1.67em");
            case "h6" -> headingDefaults(style, "0.67em", "2.33em");
            case "a" -> {
                if (style.get("display").isBlank()) style.set("display", "inline");
                if (style.get("color").isBlank()) style.set("color", "#0066cc");
                if (style.get("text-decoration").isBlank()) style.set("text-decoration", "underline");
            }
            case "strong", "b" -> style.set("font-weight", "bold");
            case "em", "i" -> style.set("font-style", "italic");
            case "pre", "code" -> {
                style.set("font-family", "monospace");
                if (tag.equals("pre")) style.set("display", "block");
            }
            case "hr" -> {
                if (style.get("display").isBlank()) style.set("display", "block");
                if (style.get("border").isBlank()) {
                    style.set("border-top", "1px solid #8c8c8c");
                    style.set("border-bottom", "none");
                }
                if (style.get("margin").isBlank()) {
                    style.set("margin-top", "0.5em");
                    style.set("margin-bottom", "0.5em");
                }
            }
            case "input", "button", "select", "textarea" -> {
                if (style.get("display").isBlank()) style.set("display", "inline-block");
                if (style.get("font-family").isBlank()) style.set("font-family", "sans-serif");
                if (style.get("border").isBlank()) style.set("border", "1px solid #767676");
                if (style.get("padding").isBlank()) {
                    style.set("padding-top", "1px");
                    style.set("padding-bottom", "1px");
                    style.set("padding-left", "2px");
                    style.set("padding-right", "2px");
                }
            }
            case "table" -> {
                if (style.get("display").isBlank()) style.set("display", "block"); // no real table layout yet
            }
            case "tr", "thead", "tbody", "tfoot" -> {
                if (style.get("display").isBlank()) style.set("display", "block");
            }
            case "td", "th" -> {
                if (style.get("display").isBlank()) style.set("display", "inline-block");
                if (tag.equals("th")) style.set("font-weight", "bold");
            }

            case "center" -> {
                if (style.get("display").isBlank()) {
                    style.set("display", "block");
                }
                style.set("text-align", "center");
            }
        }
    }

    private void headingDefaults(ComputedStyle style, String fontSize, String margin) {
        if (style.get("display").isBlank()) style.set("display", "block");
        if (style.get("font-size").isBlank()) style.set("font-size", fontSize);
        if (style.get("font-weight").isBlank()) style.set("font-weight", "bold");
        if (style.get("margin").isBlank()) {
            style.set("margin-top", margin);
            style.set("margin-bottom", margin);
        }
    }

    private static String loadStylesheet(String href, BrowserLoader loader)
            throws Exception {

        if (href.regionMatches(true, 0, "file:", 0, 5)) {
            Path path = Path.of(URI.create(href));

            Logger.info("[LOCAL] Loaded stylesheet: {}", path);

            return Files.readString(
                    path,
                    StandardCharsets.UTF_8
            );
        }

        return loader.loadText(href);
    }

    public static String collectAuthorCss(Document doc, BrowserLoader loader) {
        StringBuilder css = new StringBuilder();

        // Inline CSS (including styles injected by JS via createElement('style')
        // + textContent / appendChild(createTextNode(...))). Prefer .data()
        // (DataNodes) but fall back to .html()/.text() for edge cases where
        // content landed as TextNodes instead.
        //
        // Skip <style> inside <noscript>/<template>/<script>. Google's search
        // pages ship <noscript><style>table,div,span,p{display:none}</style>
        // which would blank the entire document if applied while JS is "on".
        for (Element style : doc.select("style")) {
            if (isInsideNonCssHost(style)) {
                continue;
            }

            String content = style.data();
            if (content == null || content.isBlank()) {
                content = style.html();
            }
            if (content == null || content.isBlank()) {
                content = style.text();
            }
            if (content != null && !content.isBlank()) {
                css.append(content).append('\n');
            }
        }

        // External CSS resources
        for (Element link : doc.select("link")) {
            String rel = link.attr("rel");

            if (!rel.toLowerCase().contains("stylesheet")) {
                continue;
            }

            String href = link.absUrl("href");
            if (href.isBlank()) {
                href = link.attr("href");
            }

            if (href.isBlank()) {
                continue;
            }

            if (href.contains("google.com/xjs/_/ss/")) {
                Logger.info("[CSS] skipping known empty Google stylesheet: {}", href);
                continue;
            }

            Logger.info("[CSS] discovered stylesheet: {}", href);

            try {
                String resource = loadStylesheet(href, loader);

                String extracted = extractCssDocument(resource);

                if (!extracted.isBlank()) {
                    css.append(extracted).append('\n');
                } else {
                    Logger.warn(
                            "[CSS] stylesheet contained no CSS: {}",
                            href
                    );
                }
            } catch (Exception e) {
                Logger.warn(
                        "[CSS] failed to load stylesheet {}: {}",
                        href,
                        e.getMessage()
                );
            }
        }

        // Google legacy renderer fallback.
// Google already sends the classic CSS inline; this only patches
// properties that PeakyBrowser's renderer may not handle correctly.
        String pageUrl = doc.baseUri();

        if (pageUrl != null
                && (pageUrl.contains("google.com/webhp")
                || pageUrl.contains("google.com/?"))) {

            css.append("""
        
        /* PeakyBrowser Google legacy fallback */
        html, body {
            margin: 0 !important;
            padding: 0 !important;
            background: #fff !important;
            color: #222 !important;
            font-family: Arial, sans-serif !important;
        }

        body {
            overflow-y: scroll !important;
        }

        form {
            margin-bottom: 20px !important;
        }

        .lst {
            width: 496px !important;
            height: 25px !important;
        }

        .lsbb {
            height: 30px !important;
            background: #f3f5f6 !important;
            border: 1px solid #dadce0 !important;
        }

        .lsb {
            height: 30px !important;
            background: #f3f5f6 !important;
            border: 1px solid #dadce0 !important;
        }

        .h {
            color: #1558d6 !important;
        }

        a {
            color: #681da8 !important;
            text-decoration: none !important;
        }

        a:hover,
        a:active {
            text-decoration: underline !important;
        }
        
        """);
        }

        return css.toString();
    }

    /**
     * True when {@code el} lives under {@code <noscript>}, {@code <template>},
     * or {@code <script>} — hosts whose contents must not affect live styling.
     */
    private static boolean isInsideNonCssHost(Element el) {
        for (Element p = el.parent(); p != null; p = p.parent()) {
            String tag = p.tagName();
            if ("noscript".equalsIgnoreCase(tag)
                    || "template".equalsIgnoreCase(tag)
                    || "script".equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    private static String extractCssDocument(String resource) {
        if (resource == null || resource.isBlank()) {
            return "";
        }

        String trimmed = resource.trim();

        if (!trimmed.startsWith("<")) {
            return trimmed;
        }

        Document document = Jsoup.parse(trimmed);

        Element pre = document.selectFirst("pre");

        if (pre != null) {
            String css = pre.text();

            if (!css.isBlank()) {
                return css;
            }
        }

        return "";
    }

    private static final Pattern VAR_FUNCTION =
            Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*(?:,\\s*([^)]+))?\\)");

    private String resolveVars(String value) {
        if (value == null || !value.contains("var(")) {
            return value;
        }
        Matcher m = VAR_FUNCTION.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(value, last, m.start());
            String name = m.group(1);
            String fallback = m.group(2);
            String resolved = customProperties.get(name);
            out.append(resolved != null ? resolved : (fallback != null ? fallback.trim() : ""));
            last = m.end();
        }
        out.append(value.substring(last));
        String result = out.toString();
        return result.contains("var(") ? resolveVars(result) : result;
    }

    public Map<String, String> getCustomProperties() {
        return customProperties;
    }
}