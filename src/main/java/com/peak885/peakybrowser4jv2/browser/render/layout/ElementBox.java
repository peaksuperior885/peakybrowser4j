package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.image.ImageData;
import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;
import org.jsoup.nodes.Element;

import java.awt.*;
import java.awt.image.BufferedImage;

public final class ElementBox extends Box {

    private final Element element;
    private final ImageData captchaImage;
    private boolean focused;

    public ElementBox(ComputedStyle style, Element element) {
        this(style, element, null);
    }

    public ElementBox(
            ComputedStyle style,
            Element element,
            ImageData captchaImage
    ) {
        super(style);
        this.element = element;
        this.captchaImage = captchaImage;
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

    private void paintInput(Graphics2D g) {
        fillControlBackground(g, Color.WHITE, true);

        // Focus ring
        if (focused) {
            g.setColor(new Color(60, 120, 220));
            g.setStroke(new BasicStroke(2f));
        } else {
            g.setColor(new Color(150, 150, 150));
            g.setStroke(new BasicStroke(1f));
        }
        g.drawRoundRect(Math.round(x), Math.round(y), Math.round(width), Math.round(height), 6, 6);
        g.setStroke(new BasicStroke(1f));

        String type = element.attr("type").toLowerCase();
        String display;
        if (type.equals("password")) {
            String raw = value();
            display = "*".repeat(raw.length());
        } else {
            display = value();
            if (display.isBlank() && element.hasAttr("placeholder")) {
                display = element.attr("placeholder");
                g.setColor(new Color(120, 120, 120));
            } else {
                g.setColor(style.color());
            }
        }

        if (!display.isBlank()) {
            g.setFont(createFont());
            FontMetrics fm = g.getFontMetrics();
            int textX = Math.round(x + 8);
            int textY = Math.round(y + (height - fm.getHeight()) / 2f + fm.getAscent());
            // Clip to control width
            Shape oldClip = g.getClip();
            g.clipRect(Math.round(x + 4), Math.round(y), Math.round(width - 8), Math.round(height));
            g.drawString(display, textX, textY);
            g.setClip(oldClip);
        }
    }

    private void paintButton(Graphics2D g) {
        Color fallback = focused ? new Color(220, 230, 250) : new Color(240, 240, 240);
        fillControlBackground(g, fallback, true);

        g.setColor(focused ? new Color(60, 120, 220) : new Color(140, 140, 140));
        g.setStroke(new BasicStroke(focused ? 2f : 1f));
        g.drawRoundRect(Math.round(x), Math.round(y), Math.round(width), Math.round(height), 6, 6);
        g.setStroke(new BasicStroke(1f));

        String text = element.text();
        if (text.isBlank()) {
            text = element.attr("value");
        }
        g.setFont(createFont());
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textX = Math.round(x + (width - textWidth) / 2f);
        int textY = Math.round(y + (height - fm.getHeight()) / 2f + fm.getAscent());
        g.setColor(style.color());
        g.drawString(text, textX, textY);
    }

    private void paintTextarea(Graphics2D g) {
        fillControlBackground(g, Color.WHITE, false);

        if (focused) {
            g.setColor(new Color(60, 120, 220));
            g.setStroke(new BasicStroke(2f));
        } else {
            g.setColor(new Color(150, 150, 150));
            g.setStroke(new BasicStroke(1f));
        }
        g.drawRect(Math.round(x), Math.round(y), Math.round(width), Math.round(height));
        g.setStroke(new BasicStroke(1f));

        String text = value();
        if (!text.isBlank()) {
            g.setFont(createFont());
            g.setColor(style.color());
            FontMetrics fm = g.getFontMetrics();
            int lineH = fm.getHeight();
            int textX = Math.round(x + 6);
            int textY = Math.round(y + 4 + fm.getAscent());
            Shape oldClip = g.getClip();
            g.clipRect(Math.round(x + 2), Math.round(y + 2), Math.round(width - 4), Math.round(height - 4));
            // Simple multi-line (split on \n only)
            for (String line : text.split("\n", -1)) {
                g.drawString(line, textX, textY);
                textY += lineH;
                if (textY > y + height) break;
            }
            g.setClip(oldClip);
        }
    }

    private void paintSelect(Graphics2D g) {
        fillControlBackground(g, Color.WHITE, true);

        g.setColor(focused ? new Color(60, 120, 220) : new Color(150, 150, 150));
        g.setStroke(new BasicStroke(focused ? 2f : 1f));
        g.drawRoundRect(Math.round(x), Math.round(y), Math.round(width), Math.round(height), 6, 6);
        g.setStroke(new BasicStroke(1f));

        String text = element.select("option").first() != null
                ? element.select("option").first().text()
                : "";
        g.setFont(createFont());
        g.setColor(style.color());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text,
                Math.round(x + 8),
                Math.round(y + (height - fm.getHeight()) / 2f + fm.getAscent()));
    }

    /**
     * Fills the control with {@link ComputedStyle#backgroundPaint}, which
     * already routes through {@link com.peak885.peakybrowser4jv2.browser.render.style.GradientParser}
     * for linear-/radial-gradient. Falls back to {@code fallback} when the
     * resolved paint is null or fully transparent.
     */
    private void fillControlBackground(Graphics2D g, Color fallback, boolean rounded) {
        int ix = Math.round(x);
        int iy = Math.round(y);
        int iw = Math.round(width);
        int ih = Math.round(height);
        int diameter = rounded ? 6 : 0;

        paintBoxShadow(g, ix, iy, iw, ih, diameter);

        Paint background = style.backgroundPaint(width, height);
        boolean visible = false;
        if (background != null) {
            if (background instanceof Color color) {
                visible = color.getAlpha() > 0;
            } else {
                // Gradients / other paints are always drawn
                visible = true;
            }
        }
        if (visible) {
            g.setPaint(background);
        } else {
            g.setColor(fallback);
        }
        if (rounded) {
            g.fillRoundRect(ix, iy, iw, ih, 6, 6);
        } else {
            g.fillRect(ix, iy, iw, ih);
        }
    }

    private void paintBoxShadow(
            Graphics2D g,
            int bx,
            int by,
            int bw,
            int bh,
            int diameter
    ) {
        ComputedStyle.BoxShadow shadow = style.boxShadow();
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
