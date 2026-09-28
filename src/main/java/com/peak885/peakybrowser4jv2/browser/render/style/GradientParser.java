package com.peak885.peakybrowser4jv2.browser.render.style;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses CSS linear-gradient() (and the background / background-image shorthand
 * forms that contain it) into a Java2D LinearGradientPaint.
 *
 * Supported:
 *   linear-gradient(to bottom, red, blue)
 *   linear-gradient(45deg, #f00, #00f)
 *   linear-gradient(to right, rgb(255,0,0) 0%, rgba(0,0,255,0.5) 100%)
 *   background: linear-gradient(...)
 *   background-image: linear-gradient(...)
 */
public final class GradientParser {

    private GradientParser() {
    }

    public static Paint parse(String value, float width, float height, ComputedStyle style) {
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();

        String lower = value.toLowerCase(Locale.ROOT);
        int linearStart = lower.indexOf("linear-gradient(");
        int radialStart = lower.indexOf("radial-gradient(");

        if (radialStart >= 0 && (linearStart < 0 || radialStart < linearStart)) {
            Paint radial = parseRadial(value, radialStart, width, height, style);
            if (radial != null) {
                return radial;
            }
        }

        if (linearStart >= 0) {
            Paint linear = parseLinear(value, linearStart, width, height, style);
            if (linear != null) {
                return linear;
            }
        }
        return parseTrailingSolidColor(value, style);
    }

    private static Paint parseLinear(String value, int start, float width, float height, ComputedStyle style) {
        int open = value.indexOf('(', start);
        if (open < 0) {
            return null;
        }

        int close = findMatchingParen(value, open);
        if (close < 0) {
            return null;
        }

        String inner = value.substring(open + 1, close).trim();
        List<String> parts = splitCommaPreservingParens(inner);

        if (parts.size() < 2) {
            return null;
        }

        float angle = 180f;          // CSS default = to bottom
        int colorStart = 0;

        String first = parts.get(0).trim();
        if (isDirection(first)) {
            angle = parseDirection(first);
            colorStart = 1;
        }

        int colorCount = parts.size() - colorStart;
        if (colorCount < 2) {
            return null;
        }

        float[] fractions = new float[colorCount];
        Color[] colors = new Color[colorCount];

        for (int i = 0; i < colorCount; i++) {
            String stop = parts.get(colorStart + i).trim();
            ParsedStop parsed = parseStop(stop, style);

            if (parsed == null || parsed.color == null) {
                return null;          // hard fail → solid colour fallback
            }

            colors[i] = parsed.color;

            if (parsed.position >= 0f) {
                fractions[i] = parsed.position;
            } else {
                fractions[i] = (colorCount == 1)
                        ? 0f
                        : (float) i / (colorCount - 1);
            }
        }

        normalizeFractions(fractions);

        float cx = width * 0.5f;
        float cy = height * 0.5f;

        double radians = Math.toRadians(angle);
        float dx = (float) Math.sin(radians);
        float dy = (float) -Math.cos(radians);

        float halfDiag = (float) (0.5 * Math.hypot(width, height));

        float x1 = cx - dx * halfDiag;
        float y1 = cy - dy * halfDiag;
        float x2 = cx + dx * halfDiag;
        float y2 = cy + dy * halfDiag;

        if (Math.abs(x1 - x2) < 0.001f && Math.abs(y1 - y2) < 0.001f) {
            x2 += 1f;
        }

        return new LinearGradientPaint(
                x1, y1, x2, y2,
                fractions, colors,
                MultipleGradientPaint.CycleMethod.NO_CYCLE
        );
    }

    private static Paint parseTrailingSolidColor(String value, ComputedStyle style) {
        List<String> layers = splitCommaPreservingParens(value);
        String last = layers.get(layers.size() - 1).trim();
        Color c = parseColorExpression(last.toLowerCase(Locale.ROOT), style);
        return c;
    }

    private static Paint parseRadial(String value, int start, float width, float height, ComputedStyle style) {
        int open = value.indexOf('(', start);
        int close = findMatchingParen(value, open);
        if (open < 0 || close < 0) return null;

        String inner = value.substring(open + 1, close).trim();
        List<String> parts = splitCommaPreservingParens(inner);
        if (parts.isEmpty()) return null;

        float cx = width * 0.5f, cy = height * 0.5f;
        int colorStart = 0;

        // Optional leading "at X% Y%" (shape/size keywords ignored - approximate)
        String first = parts.get(0).trim().toLowerCase(Locale.ROOT);
        if (first.startsWith("at ") || first.contains(" at ")) {
            String posPart = first.substring(first.indexOf("at ") + 3).trim();
            String[] xy = posPart.split("\\s+");
            try {
                if (xy.length >= 1 && xy[0].endsWith("%")) {
                    cx = width * Float.parseFloat(xy[0].replace("%", "")) / 100f;
                }
                if (xy.length >= 2 && xy[1].endsWith("%")) {
                    cy = height * Float.parseFloat(xy[1].replace("%", "")) / 100f;
                }
            } catch (NumberFormatException ignored) { }
            colorStart = 1;
        }

        int colorCount = parts.size() - colorStart;
        if (colorCount < 1) return null;

        float[] fractions = new float[colorCount];
        Color[] colors = new Color[colorCount];
        for (int i = 0; i < colorCount; i++) {
            ParsedStop stop = parseStop(parts.get(colorStart + i).trim(), style);
            if (stop == null || stop.color == null) return null;
            colors[i] = stop.color;
            fractions[i] = stop.position >= 0f ? stop.position
                    : (colorCount == 1 ? 0f : (float) i / (colorCount - 1));
        }
        if (colorCount == 1) {
            // RadialGradientPaint needs >= 2 stops
            colors = new Color[]{colors[0], new Color(0, 0, 0, 0)};
            fractions = new float[]{0f, 1f};
        }
        normalizeFractions(fractions);

        float radius = Math.max(1f, (float) Math.hypot(width, height) * 0.7f);
        return new java.awt.RadialGradientPaint(
                new java.awt.geom.Point2D.Float(cx, cy), radius, fractions, colors);
    }

    // ------------------------------------------------------------------
    // Direction / angle parsing
    // ------------------------------------------------------------------

    private static boolean isDirection(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("to ")
                || v.endsWith("deg")
                || v.endsWith("grad")
                || v.endsWith("rad")
                || v.endsWith("turn");
    }

    private static float parseDirection(String value) {
        value = value.trim().toLowerCase(Locale.ROOT);

        if (value.startsWith("to ")) {
            String dir = value.substring(3).trim();

            boolean top    = dir.contains("top");
            boolean bottom = dir.contains("bottom");
            boolean left   = dir.contains("left");
            boolean right  = dir.contains("right");

            if (top && right)    return 45f;
            if (bottom && right) return 135f;
            if (bottom && left)  return 225f;
            if (top && left)     return 315f;

            if (right)  return 90f;
            if (bottom) return 180f;
            if (left)   return 270f;
            if (top)    return 0f;
        }

        try {
            if (value.endsWith("deg")) {
                return Float.parseFloat(value.substring(0, value.length() - 3));
            }
            if (value.endsWith("turn")) {
                return Float.parseFloat(value.substring(0, value.length() - 4)) * 360f;
            }
            if (value.endsWith("rad")) {
                return (float) Math.toDegrees(
                        Float.parseFloat(value.substring(0, value.length() - 3)));
            }
            if (value.endsWith("grad")) {
                return Float.parseFloat(value.substring(0, value.length() - 4)) * 0.9f;
            }
        } catch (NumberFormatException ignored) {
        }

        return 180f; // CSS default
    }

    // ------------------------------------------------------------------
    // Color-stop parsing  (the part that was broken)
    // ------------------------------------------------------------------

    /**
     * A stop looks like:
     *   red
     *   #f00 30%
     *   rgb(255, 0, 0)
     *   rgba(0, 128, 255, 0.5) 75%
     *   blue 0%
     *
     * We must not split on spaces that are inside the colour function.
     */
    private static ParsedStop parseStop(String value, ComputedStyle style) {
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }

        // Walk until we leave the colour token (handles rgb(...), #hex, named)
        int i = 0;
        int len = value.length();

        // Skip leading whitespace (already trimmed, but safe)
        while (i < len && Character.isWhitespace(value.charAt(i))) {
            i++;
        }

        int colorStart = i;

        // Named colour or #hex
        if (value.charAt(i) == '#' || Character.isLetter(value.charAt(i))) {
            while (i < len && !Character.isWhitespace(value.charAt(i))) {
                i++;
            }
        }
        // Functional colour: rgb( ... ) / rgba( ... ) / hsl( ... ) etc.
        else if (i + 3 < len && value.regionMatches(true, i, "rgb", 0, 3)
                || value.regionMatches(true, i, "hsl", 0, 3)
                || value.regionMatches(true, i, "hwb", 0, 3)) {

            // Find the matching parenthesis
            int open = value.indexOf('(', i);
            if (open < 0) {
                return null;
            }
            int close = findMatchingParen(value, open);
            if (close < 0) {
                return null;
            }
            i = close + 1;
        } else {
            // Unknown token – try treating the whole thing as a colour
            i = len;
        }

        String colorToken = value.substring(colorStart, i).trim();
        Color color = parseColorExpression(colorToken, style);
        if (color == null) {
            return null;
        }

        // Optional position after the colour
        float position = -1f;
        String rest = value.substring(i).trim();
        if (!rest.isEmpty()) {
            // Take the first token only (we ignore extra positions for now)
            String posToken = rest.split("\\s+")[0];
            try {
                if (posToken.endsWith("%")) {
                    position = Float.parseFloat(
                            posToken.substring(0, posToken.length() - 1)) / 100f;
                }
                // px stops would need the gradient length; leave as auto for now
            } catch (NumberFormatException ignored) {
            }
        }

        return new ParsedStop(color, position);
    }

    private static Color parseColorExpression(String value, ComputedStyle style) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String v = value.trim().toLowerCase(Locale.ROOT);

        // Only accept things that look like real colours so we never
        // turn "1.5px" into black.
        if (v.startsWith("#")
                || v.startsWith("rgb(")
                || v.startsWith("rgba(")
                || v.startsWith("hsl(")
                || v.startsWith("hsla(")
                || isLikelyNamedColor(v)) {

            try {
                Color c = style.parseColor(v);
                // style.parseColor falls back to black; we only want a real match
                if (c != null && (c.getRGB() != 0xFF000000 || v.equals("black") || v.equals("#000") || v.equals("#000000"))) {
                    return c;
                }
                // Special-case transparent
                if (v.equals("transparent")) {
                    return new Color(0, 0, 0, 0);
                }
                // For pure black we already accepted it above when the name matches
                if (v.equals("black") || v.equals("#000") || v.equals("#000000")) {
                    return Color.BLACK;
                }
                // Re-parse carefully without the black fallback
                return style.parseColor(v); // still safe for real colours
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isLikelyNamedColor(String value) {
        return value.matches("[a-zA-Z]+");
    }

    // ------------------------------------------------------------------
    // Fraction normalisation (Java2D is picky)
    // ------------------------------------------------------------------

    private static void normalizeFractions(float[] fractions) {
        if (fractions.length == 0) {
            return;
        }

        // Clamp first
        fractions[0] = clamp01(fractions[0]);

        // Force non-decreasing
        for (int i = 1; i < fractions.length; i++) {
            fractions[i] = Math.max(fractions[i - 1], clamp01(fractions[i]));
        }

        // Guarantee last stop is exactly 1.0
        fractions[fractions.length - 1] = 1f;

        // Java2D demands strictly increasing values
        for (int i = 1; i < fractions.length; i++) {
            if (fractions[i] <= fractions[i - 1]) {
                fractions[i] = Math.min(1f, fractions[i - 1] + 0.0001f);
            }
        }

        // Final safety
        if (fractions.length > 1
                && fractions[fractions.length - 1] <= fractions[fractions.length - 2]) {
            fractions[fractions.length - 1] = 1f;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int findMatchingParen(String value, int open) {
        int depth = 0;
        for (int i = open; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitCommaPreservingParens(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(text.substring(start).trim());
        return result;
    }

    private static final class ParsedStop {
        final Color color;
        final float position;   // -1 = auto

        ParsedStop(Color color, float position) {
            this.color = color;
            this.position = position;
        }
    }
}