package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.js.CanvasRenderingContext2D;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.jsoup.nodes.Element;

import java.awt.*;
import java.awt.image.BufferedImage;

public final class ElementBox extends Box {

    private final Element element;
    private final ImageData captchaImage;
    private final CanvasRenderingContext2D canvas;
    private boolean focused;

    public ElementBox(ComputedStyle style, Element element) {
        this(style, element, null, null);
    }

    public ElementBox(
            ComputedStyle style,
            Element element,
            ImageData captchaImage
    ) {
        this(style, element, captchaImage, null);
    }

    public ElementBox(ComputedStyle style, Element element, ImageData captchaImage, CanvasRenderingContext2D canvas) {
        super(style);
        this.element = element;
        this.captchaImage = captchaImage;
        this.canvas = canvas;
        this.sourceElement = element;
    }

    public Element element() {
        return element;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    /** Live value for text-like controls (kept in sync with the DOM attribute). */
    public String value() {
        String tag = element.tagName().toLowerCase();
        if ("textarea".equals(tag)) {
            return element.text();
        }
        String v = element.attr("value");
        if (v.isBlank() && element.hasAttr("placeholder")) {
            // placeholder is display-only; real value is empty
            return element.hasAttr("value") ? v : "";
        }
        return v;
    }

    public void setValue(String value) {
        String tag = element.tagName().toLowerCase();
        if ("textarea".equals(tag)) {
            element.text(value == null ? "" : value);
        } else {
            element.attr("value", value == null ? "" : value);
        }
    }

    public boolean isTextual() {
        String tag = element.tagName().toLowerCase();
        if ("textarea".equals(tag)) return true;
        if (!"input".equals(tag)) return false;
        String type = element.attr("type").toLowerCase();
        return type.isEmpty()
                || type.equals("text")
                || type.equals("password")
                || type.equals("email")
                || type.equals("search")
                || type.equals("url")
                || type.equals("tel")
                || type.equals("number");
    }

    public boolean isButtonLike() {
        String tag = element.tagName().toLowerCase();
        if ("button".equals(tag)) return true;
        if (!"input".equals(tag)) return false;
        String type = element.attr("type").toLowerCase();
        return type.equals("submit") || type.equals("button") || type.equals("reset");
    }

    @Override
    public void layout(float availableWidth, float startX, float startY) {
        float mt = style.marginTop();
        float mr = style.marginRight();
        float mb = style.marginBottom();
        float ml = style.marginLeft();

        float pt = style.paddingTop();
        float pr = style.paddingRight();
        float pb = style.paddingBottom();
        float pl = style.paddingLeft();

        borderBoxX = startX + ml;
        borderBoxY = startY + mt;

        float defaultWidth = captchaImage == null
                ? switch (element.tagName().toLowerCase()) {
                    case "input" -> 180f;
                    case "button" -> 100f;
                    case "textarea" -> 250f;
                    case "select" -> 180f;
                    default -> 100f;
                }
                : captchaImage.intrinsicWidth();

        float defaultHeight = captchaImage == null
                ? switch (element.tagName().toLowerCase()) {
                    case "textarea" -> 80f;
                    default -> 32f;
                }
                : captchaImage.intrinsicHeight();

        float contentWidth = style.width(defaultWidth, style.fontSize(), availableWidth);
        float contentHeight = style.height(defaultHeight, style.fontSize(), availableWidth);

        width = Math.max(0, contentWidth);
        height = Math.max(0, contentHeight);

        borderBoxWidth = width + pl + pr;
        borderBoxHeight = height + pt + pb;

        x = borderBoxX + pl;
        y = borderBoxY + pt;
    }

    @Override
    public void paint(Graphics2D g) {
        if (canvas != null) {
            g.drawImage(canvas.image(), Math.round(x), Math.round(y), Math.max(0, Math.round(width)), Math.max(0, Math.round(height)), null);
            return;
        }
        if (captchaImage != null) {
            paintCaptchaImage(g);
            return;
        }

        String tag = element.tagName().toLowerCase();
        switch (tag) {
            case "input" -> paintInput(g);
            case "button" -> paintButton(g);
            case "textarea" -> paintTextarea(g);
            case "select" -> paintSelect(g);
            default -> {}
        }
    }

    private void paintCaptchaImage(Graphics2D g) {
        BufferedImage frame = captchaImage.firstFrame();
        if (frame == null) {
            return;
        }

        g.drawImage(
                frame,
                Math.round(x),
                Math.round(y),
                Math.max(1, Math.round(width)),
                Math.max(1, Math.round(height)),
                null
        );
    }

    // ------------------------------------------------------------------
    // Style-driven control painting (fully CSS / transition aware)
    // ------------------------------------------------------------------

    private void paintInput(Graphics2D g) {
        Color fallback = Color.WHITE;
        paintControlChrome(g, fallback);

        String type = element.attr("type").toLowerCase();
        String display;
        Color textColor = style.color();
        if (type.equals("password")) {
            String raw = value();
            display = "*".repeat(raw.length());
        } else {
            display = value();
            if (display.isBlank() && element.hasAttr("placeholder")) {
                display = element.attr("placeholder");
                textColor = new Color(120, 120, 120);
            }
        }

        if (!display.isBlank()) {
            g.setFont(createFont());
            g.setColor(textColor);
            FontMetrics fm = g.getFontMetrics();
            float padL = effectivePaddingLeft();
            int textX = Math.round(borderBoxX + padL);
            int textY = Math.round(borderBoxY + (borderBoxHeight - fm.getHeight()) / 2f + fm.getAscent());
            Shape oldClip = g.getClip();
            g.clipRect(
                    Math.round(borderBoxX + 2),
                    Math.round(borderBoxY),
                    Math.max(0, Math.round(borderBoxWidth - 4)),
                    Math.round(borderBoxHeight)
            );
            g.drawString(display, textX, textY);
            g.setClip(oldClip);
        }
    }

    private void paintButton(Graphics2D g) {
        Color fallback = focused ? new Color(220, 230, 250) : new Color(240, 240, 240);
        paintControlChrome(g, fallback);

        String text = element.text();
        if (text.isBlank()) {
            text = element.attr("value");
        }
        g.setFont(createFont());
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textX = Math.round(borderBoxX + (borderBoxWidth - textWidth) / 2f);
        int textY = Math.round(borderBoxY + (borderBoxHeight - fm.getHeight()) / 2f + fm.getAscent());
        g.setColor(style.color());
        g.drawString(text, textX, textY);
    }

    private void paintTextarea(Graphics2D g) {
        paintControlChrome(g, Color.WHITE);

        String text = value();
        if (!text.isBlank()) {
            g.setFont(createFont());
            g.setColor(style.color());
            FontMetrics fm = g.getFontMetrics();
            int lineH = fm.getHeight();
            float padL = effectivePaddingLeft();
            float padT = effectivePaddingTop();
            int textX = Math.round(borderBoxX + padL);
            int textY = Math.round(borderBoxY + padT + fm.getAscent());
            Shape oldClip = g.getClip();
            g.clipRect(
                    Math.round(borderBoxX + 2),
                    Math.round(borderBoxY + 2),
                    Math.max(0, Math.round(borderBoxWidth - 4)),
                    Math.max(0, Math.round(borderBoxHeight - 4))
            );
            for (String line : text.split("\n", -1)) {
                g.drawString(line, textX, textY);
                textY += lineH;
                if (textY > borderBoxY + borderBoxHeight) break;
            }
            g.setClip(oldClip);
        }
    }

    private void paintSelect(Graphics2D g) {
        paintControlChrome(g, Color.WHITE);

        String text = "Select…";
        for (org.jsoup.nodes.Element opt : element.select("option")) {
            if (opt.hasAttr("selected") || text.equals("Select…")) {
                String t = opt.text();
                if (!t.isBlank()) {
                    text = t;
                    if (opt.hasAttr("selected")) break;
                }
            }
        }
        g.setFont(createFont());
        FontMetrics fm = g.getFontMetrics();
        float padL = effectivePaddingLeft();
        g.setColor(style.color());
        g.drawString(
                text,
                Math.round(borderBoxX + padL),
                Math.round(borderBoxY + (borderBoxHeight - fm.getHeight()) / 2f + fm.getAscent())
        );

        // Simple dropdown chevron on the right
        int chevronSize = 4;
        int cx = Math.round(borderBoxX + borderBoxWidth - Math.max(12, effectivePaddingRight()) - chevronSize);
        int cy = Math.round(borderBoxY + borderBoxHeight / 2f);
        g.setColor(style.color());
        g.fillPolygon(
                new int[]{cx - chevronSize, cx + chevronSize, cx},
                new int[]{cy - 2, cy - 2, cy + chevronSize},
                3
        );
    }

    /**
     * Shared chrome for form controls: box-shadow, background (incl. animated
     * transitions), border, and optional focus ring. All values come from
     * {@link ComputedStyle} so CSS transitions on background / border-color /
     * border-radius / box-shadow actually animate.
     */
    private void paintControlChrome(Graphics2D g, Color fallbackBg) {
        int bx = Math.round(borderBoxX);
        int by = Math.round(borderBoxY);
        int bw = Math.round(borderBoxWidth);
        int bh = Math.round(borderBoxHeight);
        if (bw <= 0 || bh <= 0) return;

        float radius = style.borderRadius();
        // Sensible default when author CSS does not set radius
        if (radius <= 0f) {
            radius = 4f;
        }
        int diameter = Math.round(radius * 2);

        long now = System.currentTimeMillis();
        Color animBg = null;
        ComputedStyle.BoxShadow animShadow = null;
        ComputedStyle.Transform tx = style.transform();
        if (anim != null) {
            if (anim.done(now)) {
                anim = null;
            } else {
                animBg = anim.background(now);
                animShadow = anim.shadow(now);
                tx = anim.transform(now);
            }
        }

        java.awt.geom.AffineTransform saved = null;
        if (!tx.isIdentity()) {
            saved = g.getTransform();
            float cx = borderBoxX + borderBoxWidth / 2f;
            float cy = borderBoxY + borderBoxHeight / 2f;
            g.translate(cx + tx.translateX(), cy + tx.translateY());
            g.rotate(Math.toRadians(tx.rotateDeg()));
            g.scale(tx.scaleX(), tx.scaleY());
            g.translate(-cx, -cy);
        }

        // Shadow (animated when a transition is active)
        paintBoxShadow(g, bx, by, bw, bh, diameter, animShadow);

        // Background – prefer animated solid color, else style paint, else fallback
        if (animBg != null) {
            g.setColor(animBg);
            fillRounded(g, bx, by, bw, bh, diameter);
        } else {
            Paint background = style.backgroundPaint(borderBoxWidth, borderBoxHeight);
            boolean visible = false;
            if (background != null) {
                if (background instanceof Color color) {
                    visible = color.getAlpha() > 0;
                } else {
                    visible = true; // gradients
                }
            }
            if (visible) {
                g.setPaint(background);
            } else {
                g.setColor(fallbackBg);
            }
            fillRounded(g, bx, by, bw, bh, diameter);
        }

        // Border
        float borderW = Math.max(
                style.borderTopWidth(),
                Math.max(style.borderRightWidth(),
                        Math.max(style.borderBottomWidth(), style.borderLeftWidth()))
        );
        if (borderW <= 0f) {
            borderW = 1f; // default hairline for form controls
        }
        Color borderColor = style.borderColor();
        if (borderColor == null || borderColor.getAlpha() == 0) {
            borderColor = focused ? new Color(60, 120, 220) : new Color(150, 150, 150);
        } else if (focused) {
            // Slightly stronger focus indication when author already set a border color
            borderColor = blendToward(borderColor, new Color(60, 120, 220), 0.45f);
            borderW = Math.max(borderW, 1.5f);
        }

        g.setColor(borderColor);
        g.setStroke(new BasicStroke(borderW));
        drawRounded(g, bx, by, bw, bh, diameter);
        g.setStroke(new BasicStroke(1f));

        if (saved != null) {
            g.setTransform(saved);
        }
    }

    private static void fillRounded(Graphics2D g, int x, int y, int w, int h, int diameter) {
        if (diameter > 0) {
            g.fillRoundRect(x, y, w, h, diameter, diameter);
        } else {
            g.fillRect(x, y, w, h);
        }
    }

    private static void drawRounded(Graphics2D g, int x, int y, int w, int h, int diameter) {
        if (diameter > 0) {
            g.drawRoundRect(x, y, w, h, diameter, diameter);
        } else {
            g.drawRect(x, y, w, h);
        }
    }

    private static Color blendToward(Color from, Color toward, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(from.getRed() + (toward.getRed() - from.getRed()) * t);
        int g = Math.round(from.getGreen() + (toward.getGreen() - from.getGreen()) * t);
        int b = Math.round(from.getBlue() + (toward.getBlue() - from.getBlue()) * t);
        int a = Math.round(from.getAlpha() + (toward.getAlpha() - from.getAlpha()) * t);
        return new Color(r, g, b, a);
    }

    private float effectivePaddingLeft() {
        float p = style.paddingLeft();
        return p > 0 ? p : 8f;
    }

    private float effectivePaddingRight() {
        float p = style.paddingRight();
        return p > 0 ? p : 8f;
    }

    private float effectivePaddingTop() {
        float p = style.paddingTop();
        return p > 0 ? p : 4f;
    }

    private void paintBoxShadow(
            Graphics2D g,
            int bx,
            int by,
            int bw,
            int bh,
            int diameter,
            ComputedStyle.BoxShadow override
    ) {
        ComputedStyle.BoxShadow shadow = override != null ? override : style.boxShadow();
        if (shadow == null || bw <= 0 || bh <= 0) {
            return;
        }

        int steps = Math.max(1, Math.min(12, Math.round(shadow.blur())));
        for (int i = steps; i >= 1; i--) {
            float t = (float) i / steps;
            int alpha = Math.max(
                    1,
                    Math.round(shadow.color().getAlpha() * (1f - t * 0.65f) / steps)
            );
            Color c = new Color(
                    shadow.color().getRed(),
                    shadow.color().getGreen(),
                    shadow.color().getBlue(),
                    alpha
            );
            g.setColor(c);
            int expand = Math.round(shadow.blur() * t);
            int sx = bx + Math.round(shadow.offsetX()) - expand;
            int sy = by + Math.round(shadow.offsetY()) - expand;
            int sw = bw + expand * 2;
            int sh = bh + expand * 2;
            if (diameter > 0) {
                g.fillRoundRect(sx, sy, sw, sh, diameter + expand, diameter + expand);
            } else {
                g.fillRect(sx, sy, sw, sh);
            }
        }
    }

    private Font createFont() {
        int fontStyle = Font.PLAIN;
        if (style.fontWeightBold()) fontStyle |= Font.BOLD;
        if (style.fontStyleItalic()) fontStyle |= Font.ITALIC;
        return new Font(style.fontFamily(), fontStyle, Math.round(style.fontSize()));
    }
}
