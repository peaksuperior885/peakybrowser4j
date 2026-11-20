package com.peak885.peakybrowser4jv2.browser.render.style;

import java.awt.Color;
import java.awt.Paint;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ComputedStyle {
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> customProperties;
    private float currentViewportWidth = 800f;

    public void set(String property, String value) {
        if (property == null || value == null) {
            return;
        }

        property = property.trim().toLowerCase();
        value = value.trim();

        if (property.isEmpty()) {
            return;
        }

        values.put(property, value);
    }

    public ComputedStyle() {
        this(Collections.emptyMap());
    }

    public ComputedStyle(Map<String, String> customProperties) {
        this.customProperties = customProperties != null
                ? customProperties
                : Collections.emptyMap();
    }

    public String get(String property) {
        if (property == null) return "";
        String value = values.getOrDefault(property.trim().toLowerCase(), "");
        return resolveVars(value);
    }

    private String resolveVars(String value) {
        if (value == null || !value.contains("var(")) {
            return value == null ? "" : value;
        }

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "var\\(\\s*(--[\\w-]+)\\s*(?:,\\s*([^)]+))?\\s*\\)");
        java.util.regex.Matcher m = p.matcher(value);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String name     = m.group(1);
            String fallback = m.group(2);
            String resolved = customProperties.getOrDefault(
                    name,
                    fallback != null ? fallback.trim() : ""
            );
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public String get(String property, String fallback) {
        String value = get(property);
        return value.isBlank() ? fallback : value;
    }

    public boolean has(String property) {
        return !get(property).isBlank();
    }

    public String display() {
        return get("display", "inline");
    }

    public boolean isBlock() {
        return switch (display().toLowerCase()) {
            case "block",
                 "list-item",
                 "table",
                 "flow-root",
                 // A block-level flex/grid container still participates in
                 // its PARENT's layout like an ordinary block box - only its
                 // own children use flex/grid formatting. Without this, a
                 // <div style="display:flex"> never got its own BlockBox at
                 // all (see LayoutEngine#buildChildren), so BlockBox's row
                 // layout for isFlex() never had a box to run on.
                 "flex",
                 "grid" -> true;

            default -> false;
        };
    }

    public boolean isInlineBlock() {
        String display = display().toLowerCase();

        // inline-flex/inline-grid establish a real box (background, padding,
        // border, sizing) exactly like inline-block does - the only
        // difference is how THEIR OWN children are formatted internally,
        // which BlockBox's isFlex()/isFlexColumn() checks handle separately.
        return display.equals("inline-block")
                || display.equals("inline-flex")
                || display.equals("inline-grid");
    }

    public boolean isInline() {
        return display().equalsIgnoreCase("inline");
    }

    public Color color() {
        Color c = parseColorSafely(get("color", "#000000"));
        if (c == null) {
            c = Color.BLACK;
        }
        // gradient-text trick: color:transparent + background-clip:text
        // Without background-clip support the title would be invisible.
        if (c.getAlpha() == 0) {
            String clip = get("background-clip", "") + " "
                    + get("-webkit-background-clip", "");
            if (clip.toLowerCase().contains("text")) {
                Color fromGradient = firstGradientStopColor();
                if (fromGradient != null && fromGradient.getAlpha() > 0) {
                    return fromGradient;
                }
                // Sensible fallback for dark themes
                return new Color(0xC4, 0xB5, 0xFD);
            }
        }
        return c;
    }

    public Color backgroundColor() {
        Color solid = parseColorSafely(get("background-color", "transparent"));
        if (solid != null && solid.getAlpha() > 0) {
            return solid;
        }
        // Multi-layer `background:` often ends with a solid base color
        // after one or more gradients (e.g. body on the maven site).
        Color fromLayers = lastSolidBackgroundLayer();
        if (fromLayers != null) {
            return fromLayers;
        }
        return solid != null ? solid : new Color(0, 0, 0, 0);
    }

    public Paint backgroundPaint(float width, float height) {
        // Prefer an explicit solid base so pages that stack
        // radial-gradient(...) , ..., #0a0b14 don't render on white.
        Color base = backgroundColor();

        String backgroundImage = get("background-image");

        if (!backgroundImage.isBlank()
                && !backgroundImage.equalsIgnoreCase("none")) {

            Paint gradient = GradientParser.parse(
                    backgroundImage,
                    width,
                    height,
                    this
            );

            if (gradient != null) {
                return gradient;
            }
        }

        String background = get("background");

        if (!background.isBlank()) {
            Paint gradient = GradientParser.parse(
                    background,
                    width,
                    height,
                    this
            );

            if (gradient != null) {
                // If the only thing we got is a soft radial, still fill with
                // the solid base first at paint time — BlockBox paints a
                // single Paint, so prefer the solid base when the gradient
                // is clearly decorative (low-alpha stops). Otherwise use
                // the gradient.
                return gradient;
            }

            // No gradient parsed — try trailing solid in the shorthand.
            Color trailing = lastSolidBackgroundLayer();
            if (trailing != null) {
                return trailing;
            }
        }

        return base;
    }

    /** First color stop of any linear/radial gradient on this style. */
    private Color firstGradientStopColor() {
        for (String src : new String[] {
                get("background-image"),
                get("background")
        }) {
            if (src == null || src.isBlank()) continue;
            String lower = src.toLowerCase();
            int idx = lower.indexOf("gradient(");
            if (idx < 0) continue;
            // Pull first #rgb / rgb() / named color after gradient(
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "#[0-9a-fA-F]{3,8}|rgba?\\([^)]+\\)|hsla?\\([^)]+\\)"
            ).matcher(src.substring(idx));
            if (m.find()) {
                Color c = parseColorSafely(m.group());
                if (c != null) return c;
            }
        }
        return null;
    }

    /**
     * For multi-layer backgrounds like
     * {@code radial-gradient(...), radial-gradient(...), #0a0b14}
     * return the last solid color layer (the base fill).
     */
    private Color lastSolidBackgroundLayer() {
        String background = get("background", "");
        if (background.isBlank()) {
            return null;
        }
        // Split on commas that are not inside parentheses.
        java.util.List<String> layers = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < background.length(); i++) {
            char ch = background.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth = Math.max(0, depth - 1);
            if (ch == ',' && depth == 0) {
                layers.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            layers.add(cur.toString().trim());
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            String layer = layers.get(i);
            if (layer.toLowerCase().contains("gradient(")) {
                continue;
            }
            // Take the last token that looks like a color.
            String[] tokens = layer.split("\\s+");
            for (int t = tokens.length - 1; t >= 0; t--) {
                Color c = parseColorSafely(tokens[t]);
                if (c != null && c.getAlpha() > 0) {
                    return c;
                }
            }
        }
        return null;
    }

    public float fontSize() {
        return parseLength(
                get("font-size", "16px"),
                16f,
                16f,
                800f,
                600f
        );
    }

    public String fontFamily() {
        String family = get("font-family", "sans-serif");

        // Use the first family for now.
        int comma = family.indexOf(',');

        if (comma >= 0) {
            family = family.substring(0, comma);
        }

        family = family.trim();

        if ((family.startsWith("\"") && family.endsWith("\""))
                || (family.startsWith("'") && family.endsWith("'"))) {

            family = family.substring(1, family.length() - 1);
        }

        return family;
    }

    public boolean fontWeightBold() {
        String weight = get("font-weight", "normal")
                .trim()
                .toLowerCase();

        if (weight.equals("bold")
                || weight.equals("bolder")) {
            return true;
        }

        try {
            return Integer.parseInt(weight) >= 700;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public boolean fontStyleItalic() {
        String value = get("font-style", "normal");
        return value.equalsIgnoreCase("italic")
                || value.equalsIgnoreCase("oblique");
    }

    public boolean textDecorationUnderline() {
        return get("text-decoration", "none")
                .toLowerCase()
                .contains("underline");
    }

    /**
     * CSS white-space value. Defaults to "normal".
     * Values we honor: normal, nowrap, pre, pre-wrap, pre-line.
     */
    public String whiteSpace() {
        String v = get("white-space", "normal").trim().toLowerCase();
        return v.isEmpty() ? "normal" : v;
    }

    public boolean preservesNewlines() {
        return switch (whiteSpace()) {
            case "pre", "pre-wrap", "pre-line" -> true;
            default -> false;
        };
    }

    public boolean preservesSpaces() {
        return switch (whiteSpace()) {
            case "pre", "pre-wrap" -> true;
            default -> false;
        };
    }

    public boolean allowsSoftWrap() {
        return switch (whiteSpace()) {
            case "nowrap", "pre" -> false;
            default -> true;
        };
    }

    public float lineHeight() {
        String value = get("line-height", "")
                .trim()
                .toLowerCase();

        float fs = fontSize();

        if (value.isEmpty() || value.equals("normal")) {
            return fs * 1.2f;
        }

        try {
            if (value.endsWith("px")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2)
                );
            }

            if (value.endsWith("em")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2)
                ) * fs;
            }

            if (value.endsWith("rem")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 3)
                ) * 16f;
            }

            if (value.endsWith("%")) {
                float percent = Float.parseFloat(
                        value.substring(0, value.length() - 1)
                );

                return percent * fs / 100f;
            }

            // Unitless line-height is multiplied by font size.
            return Float.parseFloat(value) * fs;

        } catch (NumberFormatException ignored) {
            return fs * 1.2f;
        }
    }

    public float width(
            float fallback,
            float fontSize,
            float viewportWidth
    ) {
        this.currentViewportWidth = viewportWidth;
        return parseLength(
                get("width"),
                fallback,
                fontSize,
                viewportWidth,
                600f
        );
    }

    public float height(
            float fallback,
            float fontSize,
            float viewportWidth
    ) {
        return parseLength(
                get("height"),
                fallback,
                fontSize,
                viewportWidth,
                600f
        );
    }

    public float maxWidth(
            float fallback,
            float fontSize,
            float viewportWidth
    ) {
        String value = get("max-width");

        if (value.isBlank() || value.equalsIgnoreCase("none")) {
            return fallback;
        }

        return parseLength(
                value,
                fallback,
                fontSize,
                viewportWidth,
                600f
        );
    }

    public float maxHeight(
            float fallback,
            float fontSize,
            float viewportWidth
    ) {
        String value = get("max-height");

        if (value.isBlank() || value.equalsIgnoreCase("none")) {
            return fallback;
        }

        return parseLength(
                value,
                fallback,
                fontSize,
                viewportWidth,
                600f
        );
    }

    public float minHeight(
            float fallback,
            float fontSize,
            float viewportWidth
    ) {
        String value = get("min-height");
        if (value.isBlank() || value.equalsIgnoreCase("none")) {
            return fallback;
        }
        return parseLength(
                value,
                fallback,
                fontSize,
                viewportWidth,
                600f
        );
    }

    public float marginTop() {
        return resolveBoxSide("margin", "margin-top", 0f);
    }

    public float marginRight() {
        return resolveBoxSide("margin", "margin-right", 0f);
    }

    public float marginBottom() {
        return resolveBoxSide("margin", "margin-bottom", 0f);
    }

    public float marginLeft() {
        return resolveBoxSide("margin", "margin-left", 0f);
    }

    public float paddingTop() {
        return resolveBoxSide("padding", "padding-top", 0f);
    }

    public float paddingRight() {
        return resolveBoxSide("padding", "padding-right", 0f);
    }

    public float paddingBottom() {
        return resolveBoxSide("padding", "padding-bottom", 0f);
    }

    public float paddingLeft() {
        return resolveBoxSide("padding", "padding-left", 0f);
    }

    public float borderTopWidth() {
        return resolveBoxSide(
                "border-width",
                "border-top-width",
                0f
        );
    }

    public float borderRightWidth() {
        return resolveBoxSide(
                "border-width",
                "border-right-width",
                0f
        );
    }

    public float borderBottomWidth() {
        return resolveBoxSide(
                "border-width",
                "border-bottom-width",
                0f
        );
    }

    public float borderLeftWidth() {
        return resolveBoxSide(
                "border-width",
                "border-left-width",
                0f
        );
    }

    public Color borderColor() {
        String value = get("border-color");

        if (!value.isBlank()) {
            Color parsed = parseColorSafely(value);

            if (parsed != null) {
                return parsed;
            }
        }

        // Handle:
        // border: 1.5px solid blueviolet
        String border = get("border");

        if (!border.isBlank()) {
            for (String token : splitWhitespaceAware(border)) {
                Color parsed = parseColorSafely(token);

                if (parsed != null) {
                    return parsed;
                }
            }
        }

        return color();
    }

    public String borderStyle() {
        String value = get("border-style");

        if (!value.isBlank()) {
            return firstToken(value);
        }

        String border = get("border");

        if (!border.isBlank()) {
            for (String token : splitWhitespaceAware(border)) {
                switch (token.toLowerCase()) {
                    case "none":
                    case "hidden":
                    case "dotted":
                    case "dashed":
                    case "solid":
                    case "double":
                        return token.toLowerCase();

                    default:
                        // Not a border style.
                }
            }
        }

        return "none";
    }

    public float borderRadius() {
        String value = get("border-radius", "0");

        // For now use the first radius.
        value = firstToken(value);

        return parseLength(
                value,
                0f,
                fontSize(),
                800f,
                600f
        );
    }

    /**
     * Parsed {@code box-shadow}. Supports a single outer shadow of the form
     * {@code offset-x offset-y blur-radius [spread] color} (spread is
     * accepted but ignored for painting). {@code none} / blank → null.
     */
    public BoxShadow boxShadow() {
        String value = get("box-shadow", "none").trim();
        if (value.isBlank() || value.equalsIgnoreCase("none")) {
            return null;
        }

        // Drop inset keyword if present (we only paint outer shadows)
        String lower = value.toLowerCase();
        if (lower.startsWith("inset ")) {
            value = value.substring(6).trim();
            lower = value.toLowerCase();
        }

        // Color may be rgb(...)/rgba(...)/#hex/named and can appear first or last.
        // Split into tokens while preserving function parentheses.
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                cur.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        if (tokens.isEmpty()) {
            return null;
        }

        Color color = new Color(0, 0, 0, 64);
        java.util.List<String> lengths = new java.util.ArrayList<>();
        for (String t : tokens) {
            Color parsed = parseColorSafely(t);
            if (parsed != null && !looksLikeLength(t)) {
                color = parsed;
            } else {
                lengths.add(t);
            }
        }

        float offsetX = 0f;
        float offsetY = 0f;
        float blur = 0f;
        if (lengths.size() >= 1) {
            offsetX = parseLength(lengths.get(0), 0f, fontSize(), 800f, 600f);
        }
        if (lengths.size() >= 2) {
            offsetY = parseLength(lengths.get(1), 0f, fontSize(), 800f, 600f);
        }
        if (lengths.size() >= 3) {
            blur = parseLength(lengths.get(2), 0f, fontSize(), 800f, 600f);
        }

        return new BoxShadow(offsetX, offsetY, Math.max(0f, blur), color);
    }

    private static boolean looksLikeLength(String t) {
        if (t == null || t.isEmpty()) return false;
        char c = t.charAt(0);
        return Character.isDigit(c) || c == '+' || c == '-' || c == '.';
    }

    public record BoxShadow(float offsetX, float offsetY, float blur, Color color) {}

    /**
     * Parsed CSS {@code transform}. Supports a useful subset:
     * {@code translate/translateX/translateY}, {@code scale/scaleX/scaleY},
     * {@code rotate}. Multiple functions are composed in order.
     */
    public record Transform(
            float translateX,
            float translateY,
            float scaleX,
            float scaleY,
            float rotateDeg
    ) {
        public static final Transform IDENTITY = new Transform(0, 0, 1, 1, 0);

        public boolean isIdentity() {
            return translateX == 0f && translateY == 0f
                    && scaleX == 1f && scaleY == 1f
                    && rotateDeg == 0f;
        }

        public Transform interpolate(Transform to, float t) {
            return new Transform(
                    translateX + (to.translateX - translateX) * t,
                    translateY + (to.translateY - translateY) * t,
                    scaleX + (to.scaleX - scaleX) * t,
                    scaleY + (to.scaleY - scaleY) * t,
                    rotateDeg + (to.rotateDeg - rotateDeg) * t
            );
        }
    }

    public Transform transform() {
        String value = get("transform", "none").trim();
        if (value.isBlank() || value.equalsIgnoreCase("none")) {
            return Transform.IDENTITY;
        }

        float tx = 0, ty = 0, sx = 1, sy = 1, rot = 0;
        // Match function calls: name(args)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
                .matcher(value);
        while (m.find()) {
            String fn = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String[] args = m.group(2).split(",");
            try {
                switch (fn) {
                    case "translate" -> {
                        if (args.length >= 1) {
                            tx += parseLength(args[0].trim(), 0f, fontSize(), 800f, 600f);
                        }
                        if (args.length >= 2) {
                            ty += parseLength(args[1].trim(), 0f, fontSize(), 800f, 600f);
                        }
                    }
                    case "translatex" -> {
                        if (args.length >= 1) {
                            tx += parseLength(args[0].trim(), 0f, fontSize(), 800f, 600f);
                        }
                    }
                    case "translatey" -> {
                        if (args.length >= 1) {
                            ty += parseLength(args[0].trim(), 0f, fontSize(), 800f, 600f);
                        }
                    }
                    case "scale" -> {
                        if (args.length >= 1) {
                            float s = Float.parseFloat(args[0].trim());
                            sx *= s;
                            sy *= args.length >= 2 ? Float.parseFloat(args[1].trim()) : s;
                        }
                    }
                    case "scalex" -> {
                        if (args.length >= 1) sx *= Float.parseFloat(args[0].trim());
                    }
                    case "scaley" -> {
                        if (args.length >= 1) sy *= Float.parseFloat(args[0].trim());
                    }
                    case "rotate" -> {
                        if (args.length >= 1) {
                            String a = args[0].trim().toLowerCase(java.util.Locale.ROOT);
                            if (a.endsWith("deg")) {
                                rot += Float.parseFloat(a.substring(0, a.length() - 3));
                            } else if (a.endsWith("rad")) {
                                rot += (float) Math.toDegrees(
                                        Float.parseFloat(a.substring(0, a.length() - 3)));
                            } else {
                                rot += Float.parseFloat(a);
                            }
                        }
                    }
                    default -> { /* ignore matrix/skew etc. for now */ }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new Transform(tx, ty, sx, sy, rot);
    }

    /**
     * Longest transition duration declared on this style (ms). Used to drive
     * hover/active animations. Parses {@code transition} / {@code transition-duration}.
     */
    public long transitionDurationMs() {
        String duration = get("transition-duration", "").trim();
        if (duration.isBlank()) {
            // Pull durations out of the shorthand: "prop 0.2s ease, prop 0.15s ease"
            String shorthand = get("transition", "").trim();
            if (shorthand.isBlank() || shorthand.equalsIgnoreCase("none")) {
                return 0L;
            }
            long max = 0L;
            for (String part : shorthand.split(",")) {
                max = Math.max(max, parseCssTimeToMs(extractDurationToken(part)));
            }
            return max;
        }
        long max = 0L;
        for (String part : duration.split(",")) {
            max = Math.max(max, parseCssTimeToMs(part.trim()));
        }
        return max;
    }

    private static String extractDurationToken(String transitionPart) {
        // Tokens look like: property | time | timing-function | delay
        // Time tokens end in s or ms.
        for (String tok : transitionPart.trim().split("\\s+")) {
            String t = tok.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.endsWith("ms") || (t.endsWith("s") && !t.equals("ease")
                    && !t.equals("linear") && !t.equals("ease-in")
                    && !t.equals("ease-out") && !t.equals("ease-in-out"))) {
                return t;
            }
        }
        return "0s";
    }

    private static long parseCssTimeToMs(String value) {
        if (value == null || value.isBlank()) return 0L;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (v.endsWith("ms")) {
                return Math.max(0L, (long) Float.parseFloat(v.substring(0, v.length() - 2)));
            }
            if (v.endsWith("s")) {
                return Math.max(0L, (long) (Float.parseFloat(v.substring(0, v.length() - 1)) * 1000f));
            }
            return Math.max(0L, (long) (Float.parseFloat(v) * 1000f));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static Color interpolateColor(Color from, Color to, float t) {
        if (from == null) from = new Color(0, 0, 0, 0);
        if (to == null) to = new Color(0, 0, 0, 0);
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(from.getRed() + (to.getRed() - from.getRed()) * t);
        int g = Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        int a = Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t);
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    /** CSS ease (cubic-bezier approx) — smooth start/end. */
    public static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        // ease ≈ cubic-bezier(0.25, 0.1, 0.25, 1) — use smoothstep as a solid approx
        return t * t * (3f - 2f * t);
    }

    private float resolveBoxSide(
            String shorthand,
            String longhand,
            float fallback
    ) {
        String longValue = get(longhand);

        if (!longValue.isBlank()) {
            return parseLength(
                    longValue,
                    fallback,
                    fontSize(),
                    800f,
                    600f
            );
        }

        String shorthandValue = get(shorthand);

        if (shorthandValue.isBlank()) {
            return fallback;
        }

        String[] parts = splitWhitespaceAware(shorthandValue);

        if (parts.length == 0) {
            return fallback;
        }

        int index = sideIndex(longhand);

        String selected;

        switch (parts.length) {
            case 1:
                selected = parts[0];
                break;

            case 2:
                selected = (index == 0 || index == 2)
                        ? parts[0]
                        : parts[1];
                break;

            case 3:
                if (index == 0) {
                    selected = parts[0];
                } else if (index == 1 || index == 3) {
                    selected = parts[1];
                } else {
                    selected = parts[2];
                }
                break;

            default:
                selected = parts[Math.min(index, 3)];
                break;
        }

        return parseLength(
                selected,
                fallback,
                fontSize(),
                800f,
                600f
        );
    }

    private int sideIndex(String property) {
        if (property.contains("top")) {
            return 0;
        }

        if (property.contains("right")) {
            return 1;
        }

        if (property.contains("bottom")) {
            return 2;
        }

        return 3;
    }

    private float parseLength(
            String value,
            float fallback,
            float fontSize,
            float viewportWidth,
            float viewportHeight
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        value = value.trim().toLowerCase();

        if (value.equals("auto")
                || value.equals("none")
                || value.equals("normal")) {
            return fallback;
        }

        try {
            if (value.endsWith("px")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2)
                );
            }

            if (value.endsWith("rem")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 3)
                ) * 16f;
            }

            if (value.endsWith("em")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2)
                ) * fontSize;
            }

            if (value.endsWith("vw")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2)
                ) * viewportWidth / 100f;
            }

            if (value.endsWith("vh")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 2)
                ) * viewportHeight / 100f;
            }

            if (value.endsWith("%")) {
                return Float.parseFloat(
                        value.substring(0, value.length() - 1)
                ) * viewportWidth / 100f;
            }

            return Float.parseFloat(value);

        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String firstToken(String value) {
        String[] parts = splitWhitespaceAware(value);

        return parts.length == 0 ? "" : parts[0];
    }

    private static String[] splitWhitespaceAware(String value) {
        return value.trim().split("\\s+");
    }

    private static final Map<String, Color> NAMED_COLORS =
            buildNamedColors();

    private static Map<String, Color> buildNamedColors() {
        Map<String, Color> m = new HashMap<>();

        m.put("transparent", new Color(0, 0, 0, 0));

        m.put("black", Color.BLACK);
        m.put("white", Color.WHITE);

        m.put("red", Color.RED);
        m.put("green", new Color(0, 128, 0));
        m.put("lime", Color.GREEN);
        m.put("blue", Color.BLUE);
        m.put("yellow", Color.YELLOW);
        m.put("orange", Color.ORANGE);
        m.put("purple", new Color(128, 0, 128));

        m.put("gray", Color.GRAY);
        m.put("grey", Color.GRAY);
        m.put("silver", new Color(192, 192, 192));

        m.put("darkgray", Color.DARK_GRAY);
        m.put("darkgrey", Color.DARK_GRAY);
        m.put("lightgray", Color.LIGHT_GRAY);
        m.put("lightgrey", Color.LIGHT_GRAY);

        m.put("pink", Color.PINK);
        m.put("cyan", Color.CYAN);
        m.put("magenta", Color.MAGENTA);

        m.put("brown", new Color(165, 42, 42));
        m.put("navy", new Color(0, 0, 128));
        m.put("teal", new Color(0, 128, 128));
        m.put("maroon", new Color(128, 0, 0));
        m.put("olive", new Color(128, 128, 0));

        m.put("indigo", new Color(75, 0, 130));
        m.put("gold", new Color(255, 215, 0));
        m.put("coral", new Color(255, 127, 80));
        m.put("crimson", new Color(220, 20, 60));
        m.put("salmon", new Color(250, 128, 114));
        m.put("khaki", new Color(240, 230, 140));
        m.put("violet", new Color(238, 130, 238));
        m.put("beige", new Color(245, 245, 220));
        m.put("ivory", new Color(255, 255, 240));
        m.put("turquoise", new Color(64, 224, 208));
        m.put("skyblue", new Color(135, 206, 235));
        m.put("steelblue", new Color(70, 130, 180));
        m.put("tomato", new Color(255, 99, 71));
        m.put("orchid", new Color(218, 112, 214));
        m.put("plum", new Color(221, 160, 221));
        m.put("chocolate", new Color(210, 105, 30));
        m.put("tan", new Color(210, 180, 140));

        m.put("darkred", new Color(139, 0, 0));
        m.put("darkblue", new Color(0, 0, 139));
        m.put("darkgreen", new Color(0, 100, 0));

        m.put("lightblue", new Color(173, 216, 230));
        m.put("lightgreen", new Color(144, 238, 144));
        m.put("lightyellow", new Color(255, 255, 224));

        m.put("blueviolet", new Color(138, 43, 226));

        // CSS named colors frequently used by real pages (Level 3+)
        m.put("whitesmoke", new Color(245, 245, 245));
        m.put("ghostwhite", new Color(248, 248, 255));
        m.put("snow", new Color(255, 250, 250));
        m.put("seashell", new Color(255, 245, 238));
        m.put("floralwhite", new Color(255, 250, 240));
        m.put("aliceblue", new Color(240, 248, 255));
        m.put("lavender", new Color(230, 230, 250));
        m.put("lavenderblush", new Color(255, 240, 245));
        m.put("mistyrose", new Color(255, 228, 225));
        m.put("honeydew", new Color(240, 255, 240));
        m.put("mintcream", new Color(245, 255, 250));
        m.put("azure", new Color(240, 255, 255));
        m.put("lightcyan", new Color(224, 255, 255));
        m.put("gainsboro", new Color(220, 220, 220));
        m.put("dimgray", new Color(105, 105, 105));
        m.put("dimgrey", new Color(105, 105, 105));
        m.put("slategray", new Color(112, 128, 144));
        m.put("slategrey", new Color(112, 128, 144));
        m.put("lightslategray", new Color(119, 136, 153));
        m.put("lightslategrey", new Color(119, 136, 153));
        m.put("darkslategray", new Color(47, 79, 79));
        m.put("darkslategrey", new Color(47, 79, 79));
        m.put("rebeccapurple", new Color(102, 51, 153));
        m.put("mediumblue", new Color(0, 0, 205));
        m.put("royalblue", new Color(65, 105, 225));
        m.put("dodgerblue", new Color(30, 144, 255));
        m.put("deepskyblue", new Color(0, 191, 255));
        m.put("cornflowerblue", new Color(100, 149, 237));
        m.put("mediumseagreen", new Color(60, 179, 113));
        m.put("seagreen", new Color(46, 139, 87));
        m.put("forestgreen", new Color(34, 139, 34));
        m.put("limegreen", new Color(50, 205, 50));
        m.put("springgreen", new Color(0, 255, 127));
        m.put("mediumspringgreen", new Color(0, 250, 154));
        m.put("lightseagreen", new Color(32, 178, 170));
        m.put("darkcyan", new Color(0, 139, 139));
        m.put("cadetblue", new Color(95, 158, 160));
        m.put("mediumturquoise", new Color(72, 209, 204));
        m.put("darkturquoise", new Color(0, 206, 209));
        m.put("aqua", new Color(0, 255, 255));
        m.put("aquamarine", new Color(127, 255, 212));
        m.put("palegreen", new Color(152, 251, 152));
        m.put("yellowgreen", new Color(154, 205, 50));
        m.put("olivedrab", new Color(107, 142, 35));
        m.put("darkolivegreen", new Color(85, 107, 47));
        m.put("darkkhaki", new Color(189, 183, 107));
        m.put("palegoldenrod", new Color(238, 232, 170));
        m.put("lightgoldenrodyellow", new Color(250, 250, 210));
        m.put("lemonchiffon", new Color(255, 250, 205));
        m.put("moccasin", new Color(255, 228, 181));
        m.put("peachpuff", new Color(255, 218, 185));
        m.put("bisque", new Color(255, 228, 196));
        m.put("navajowhite", new Color(255, 222, 173));
        m.put("wheat", new Color(245, 222, 179));
        m.put("burlywood", new Color(222, 184, 135));
        m.put("sandybrown", new Color(244, 164, 96));
        m.put("peru", new Color(205, 133, 63));
        m.put("saddlebrown", new Color(139, 69, 19));
        m.put("sienna", new Color(160, 82, 45));
        m.put("rosybrown", new Color(188, 143, 143));
        m.put("indianred", new Color(205, 92, 92));
        m.put("lightcoral", new Color(240, 128, 128));
        m.put("darksalmon", new Color(233, 150, 122));
        m.put("lightsalmon", new Color(255, 160, 122));
        m.put("orangered", new Color(255, 69, 0));
        m.put("darkorange", new Color(255, 140, 0));
        m.put("papayawhip", new Color(255, 239, 213));
        m.put("blanchedalmond", new Color(255, 235, 205));
        m.put("antiquewhite", new Color(250, 235, 215));
        m.put("linen", new Color(250, 240, 230));
        m.put("oldlace", new Color(253, 245, 230));
        m.put("cornsilk", new Color(255, 248, 220));
        m.put("firebrick", new Color(178, 34, 34));
        m.put("mediumvioletred", new Color(199, 21, 133));
        m.put("deeppink", new Color(255, 20, 147));
        m.put("hotpink", new Color(255, 105, 180));
        m.put("palevioletred", new Color(219, 112, 147));
        m.put("fuchsia", new Color(255, 0, 255));
        m.put("mediumorchid", new Color(186, 85, 211));
        m.put("mediumpurple", new Color(147, 112, 219));
        m.put("darkviolet", new Color(148, 0, 211));
        m.put("darkorchid", new Color(153, 50, 204));
        m.put("darkmagenta", new Color(139, 0, 139));
        m.put("slateblue", new Color(106, 90, 205));
        m.put("darkslateblue", new Color(72, 61, 139));
        m.put("mediumslateblue", new Color(123, 104, 238));

        return m;
    }

    public Color parseColor(String value) {
        Color color = parseColorSafely(value);

        return color != null ? color : Color.BLACK;
    }

    private Color parseColorSafely(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        value = value.trim().toLowerCase();

        if (value.equals("transparent")) {
            return new Color(0, 0, 0, 0);
        }

        Color named = NAMED_COLORS.get(value);

        if (named != null) {
            return named;
        }

        try {
            if (value.startsWith("#")) {

                if (value.length() == 4) {
                    int r = Character.digit(value.charAt(1), 16);
                    int g = Character.digit(value.charAt(2), 16);
                    int b = Character.digit(value.charAt(3), 16);

                    return new Color(
                            r * 17,
                            g * 17,
                            b * 17
                    );
                }

                if (value.length() == 7) {
                    return new Color(
                            Integer.parseInt(value.substring(1), 16)
                    );
                }

                if (value.length() == 9) {
                    int rgba = (int) Long.parseLong(
                            value.substring(1),
                            16
                    );

                    int r = (rgba >> 24) & 0xff;
                    int g = (rgba >> 16) & 0xff;
                    int b = (rgba >> 8) & 0xff;
                    int a = rgba & 0xff;

                    return new Color(r, g, b, a);
                }
            }

            if (value.startsWith("rgb(")
                    || value.startsWith("rgba(")) {

                int open = value.indexOf('(');
                int close = value.lastIndexOf(')');

                if (close <= open) {
                    return null;
                }

                String inside = value.substring(
                        open + 1,
                        close
                );

                String[] parts = inside.split(",");

                if (parts.length >= 3) {
                    int r = parseRgbComponent(parts[0]);
                    int g = parseRgbComponent(parts[1]);
                    int b = parseRgbComponent(parts[2]);

                    int a = 255;

                    if (parts.length >= 4) {
                        String alpha = parts[3].trim();

                        if (alpha.endsWith("%")) {
                            a = Math.round(
                                    Float.parseFloat(
                                            alpha.substring(
                                                    0,
                                                    alpha.length() - 1
                                            )
                                    ) * 2.55f
                            );
                        } else {
                            a = Math.round(
                                    Float.parseFloat(alpha) * 255f
                            );
                        }
                    }

                    return new Color(
                            clamp(r),
                            clamp(g),
                            clamp(b),
                            clamp(a)
                    );
                }
            }

            // hsl() / hsla() — common on modern sites
            if (value.startsWith("hsl(") || value.startsWith("hsla(")) {
                int open = value.indexOf('(');
                int close = value.lastIndexOf(')');
                if (close <= open) {
                    return null;
                }
                String inside = value.substring(open + 1, close);
                // Accept both comma and space separators (modern CSS)
                String[] parts = inside.split("[,\\s/]+");
                if (parts.length >= 3) {
                    float h = Float.parseFloat(parts[0].trim().replace("deg", ""));
                    float s = parsePercentOrFraction(parts[1]);
                    float l = parsePercentOrFraction(parts[2]);
                    float a = 1f;
                    if (parts.length >= 4) {
                        a = parsePercentOrFraction(parts[3]);
                        if (a > 1f) a = a / 100f; // treat >1 as percent
                    }
                    return hslToColor(h, s, l, a);
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private static float parsePercentOrFraction(String value) {
        value = value.trim();
        if (value.endsWith("%")) {
            return Float.parseFloat(value.substring(0, value.length() - 1)) / 100f;
        }
        return Float.parseFloat(value);
    }

    /** Convert HSL (h in degrees, s/l in 0..1) to a Color. */
    private static Color hslToColor(float h, float s, float l, float alpha) {
        h = ((h % 360f) + 360f) % 360f;
        s = Math.max(0f, Math.min(1f, s));
        l = Math.max(0f, Math.min(1f, l));
        alpha = Math.max(0f, Math.min(1f, alpha));

        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = l - c / 2f;

        float r1, g1, b1;
        if (h < 60f) {
            r1 = c; g1 = x; b1 = 0f;
        } else if (h < 120f) {
            r1 = x; g1 = c; b1 = 0f;
        } else if (h < 180f) {
            r1 = 0f; g1 = c; b1 = x;
        } else if (h < 240f) {
            r1 = 0f; g1 = x; b1 = c;
        } else if (h < 300f) {
            r1 = x; g1 = 0f; b1 = c;
        } else {
            r1 = c; g1 = 0f; b1 = x;
        }

        int r = Math.round((r1 + m) * 255f);
        int g = Math.round((g1 + m) * 255f);
        int b = Math.round((b1 + m) * 255f);
        int a = Math.round(alpha * 255f);
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    private static int parseRgbComponent(String value) {
        value = value.trim();

        if (value.endsWith("%")) {
            return Math.round(
                    Float.parseFloat(
                            value.substring(
                                    0,
                                    value.length() - 1
                            )
                    ) * 2.55f
            );
        }

        return Integer.parseInt(value);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public boolean isFlex() {
        String display = get("display");
        if (display == null) return false;
        String trimmed = display.trim().toLowerCase();
        return trimmed.equals("flex") || trimmed.equals("inline-flex") || trimmed.contains("flex");
    }

    public boolean isFlexWrap() {
        return get("flex-wrap", "nowrap").toLowerCase().equals("wrap");
    }

    /** True for flex-direction: column/column-reverse - stack children top-to-bottom instead of side by side. */
    public boolean isFlexColumn() {
        String direction = get("flex-direction", "row").toLowerCase();
        return direction.equals("column") || direction.equals("column-reverse");
    }

    public boolean isAbsolutelyPositioned() {
        String position = get("position").toLowerCase();
        return position.equals("absolute") || position.equals("fixed");
    }

    /** Resolved left/right/top/bottom offset, or null when unset/auto (caller should fall back to a static position). */
    public Float leftOffset(float basisWidth) {
        return offset("left", basisWidth);
    }

    public Float rightOffset(float basisWidth) {
        return offset("right", basisWidth);
    }

    public Float topOffset(float basisHeight) {
        return offset("top", basisHeight);
    }

    public Float bottomOffset(float basisHeight) {
        return offset("bottom", basisHeight);
    }

    private Float offset(String property, float basis) {
        String value = get(property);

        if (value.isBlank() || value.equalsIgnoreCase("auto")) {
            return null;
        }

        return parseLength(value, 0f, fontSize(), basis, basis);
    }

    public float gap() {
        return parseLength(
                get("gap"),
                0f,
                fontSize(),
                800f,
                600f
        );
    }

    public void setViewportWidth(float viewportWidth) {
        this.currentViewportWidth = viewportWidth;
    }

    public boolean isBorderBox() {
        return get("box-sizing", "content-box").equalsIgnoreCase("border-box");
    }

    public boolean isMarginLeftAuto() {
        String longVal = get("margin-left");
        if (longVal.equalsIgnoreCase("auto")) return true;

        // Check shorthand 'margin' (e.g., "0 auto" or "auto")
        String shorthand = get("margin");
        if (!shorthand.isBlank()) {
            String[] parts = shorthand.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equalsIgnoreCase("auto")) return true;
            if (parts.length == 4 && parts[3].equalsIgnoreCase("auto")) return true;
        }
        return false;
    }

    public boolean isMarginRightAuto() {
        String longVal = get("margin-right");
        if (longVal.equalsIgnoreCase("auto")) return true;

        String shorthand = get("margin");
        if (!shorthand.isBlank()) {
            String[] parts = shorthand.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equalsIgnoreCase("auto")) return true;
            if (parts.length == 4 && parts[1].equalsIgnoreCase("auto")) return true; // right is index 1 in 4-value shorthand
        }
        return false;
    }

    public String backgroundImageUrl() {
        String bgImage = get("background-image");
        if (bgImage.isBlank() || bgImage.equalsIgnoreCase("none")) {
            bgImage = get("background");
        }
        if (bgImage.isBlank() || !bgImage.contains("url(")) {
            return null;
        }

        int start = bgImage.indexOf("url(");
        int openParen = bgImage.indexOf('(', start);
        int closeParen = bgImage.indexOf(')', openParen);
        if (openParen < 0 || closeParen < 0) {
            return null;
        }

        String rawUrl = bgImage.substring(openParen + 1, closeParen).trim();
        // Strip surrounding quotes if present (' or ")
        if ((rawUrl.startsWith("\"") && rawUrl.endsWith("\"")) ||
                (rawUrl.startsWith("'") && rawUrl.endsWith("'"))) {
            rawUrl = rawUrl.substring(1, rawUrl.length() - 1);
        }
        return rawUrl;
    }
}
