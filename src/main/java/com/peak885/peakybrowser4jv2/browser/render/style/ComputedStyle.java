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
        return parseColor(get("color", "#000000"));
    }

    public Color backgroundColor() {
        return parseColor(get("background-color", "transparent"));
    }

    public Paint backgroundPaint(float width, float height) {
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
                return gradient;
            }
        }

        return backgroundColor();
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

        } catch (Exception ignored) {
        }

        return null;
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
}