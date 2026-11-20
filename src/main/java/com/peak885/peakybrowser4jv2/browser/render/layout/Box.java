package com.peak885.peakybrowser4jv2.browser.render.layout;

import com.peak885.peakybrowser4jv2.browser.render.style.ComputedStyle;

import org.jsoup.nodes.Element;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

public abstract class Box {

    /**
     * Computed style for this box. Not final so interactive states
     * (:hover / :active / :focus) can re-resolve styles in place without
     * rebuilding the entire box tree.
     */
    public ComputedStyle style;
    public final List<Box> children = new ArrayList<>();

    /** The DOM element this box was generated from, when known (set by LayoutEngine). Used to route real clicks back into page JS via JsBridge. */
    public Element sourceElement;

    // Content box
    public float x, y, width, height;

    // Border box (for painting background later)
    public float borderBoxX, borderBoxY, borderBoxWidth, borderBoxHeight;

    /**
     * Active CSS transition (hover/active animations). When non-null and
     * still running, paint uses interpolated values instead of jumping.
     */
    public Transition anim;

    public static final class Transition {
        public final long startMs;
        public final long durationMs;
        public final Color fromBg;
        public final Color toBg;
        public final ComputedStyle.Transform fromTx;
        public final ComputedStyle.Transform toTx;
        public final ComputedStyle.BoxShadow fromShadow;
        public final ComputedStyle.BoxShadow toShadow;

        public Transition(
                long startMs,
                long durationMs,
                Color fromBg,
                Color toBg,
                ComputedStyle.Transform fromTx,
                ComputedStyle.Transform toTx,
                ComputedStyle.BoxShadow fromShadow,
                ComputedStyle.BoxShadow toShadow
        ) {
            this.startMs = startMs;
            this.durationMs = Math.max(1L, durationMs);
            this.fromBg = fromBg;
            this.toBg = toBg;
            this.fromTx = fromTx != null ? fromTx : ComputedStyle.Transform.IDENTITY;
            this.toTx = toTx != null ? toTx : ComputedStyle.Transform.IDENTITY;
            this.fromShadow = fromShadow;
            this.toShadow = toShadow;
        }

        public float progress(long nowMs) {
            float t = (nowMs - startMs) / (float) durationMs;
            return ComputedStyle.ease(Math.max(0f, Math.min(1f, t)));
        }

        public boolean done(long nowMs) {
            return nowMs - startMs >= durationMs;
        }

        /** Null means "do not override — keep style's real background (e.g. gradient)". */
        public Color background(long nowMs) {
            if (fromBg == null || toBg == null) {
                return null;
            }
            return ComputedStyle.interpolateColor(fromBg, toBg, progress(nowMs));
        }

        public ComputedStyle.Transform transform(long nowMs) {
            return fromTx.interpolate(toTx, progress(nowMs));
        }

        public ComputedStyle.BoxShadow shadow(long nowMs) {
            float t = progress(nowMs);
            if (fromShadow == null && toShadow == null) return null;
            ComputedStyle.BoxShadow a = fromShadow != null
                    ? fromShadow
                    : new ComputedStyle.BoxShadow(0, 0, 0, new Color(0, 0, 0, 0));
            ComputedStyle.BoxShadow b = toShadow != null
                    ? toShadow
                    : new ComputedStyle.BoxShadow(0, 0, 0, new Color(0, 0, 0, 0));
            return new ComputedStyle.BoxShadow(
                    a.offsetX() + (b.offsetX() - a.offsetX()) * t,
                    a.offsetY() + (b.offsetY() - a.offsetY()) * t,
                    a.blur() + (b.blur() - a.blur()) * t,
                    ComputedStyle.interpolateColor(a.color(), b.color(), t)
            );
        }
    }

    protected Box(ComputedStyle style) {
        this.style = style;
    }

    public void replaceStyle(ComputedStyle newStyle) {
        if (newStyle != null) {
            this.style = newStyle;
        }
    }

    /**
     * Swap style and, if the element declares a transition, start animating
     * paint-only properties from the previous values toward the new ones.
     */
    public void replaceStyleAnimated(ComputedStyle newStyle) {
        if (newStyle == null) {
            return;
        }
        ComputedStyle old = this.style;
        long duration = 0L;
        if (old != null) {
            duration = Math.max(old.transitionDurationMs(), newStyle.transitionDurationMs());
        } else {
            duration = newStyle.transitionDurationMs();
        }

        // If author CSS changes transform/bg/shadow but forgot transition,
        // still ease briefly so hover feedback is visible.
        if (duration <= 0L && old != null) {
            boolean likelyInteractive =
                    !old.transform().equals(newStyle.transform())
                    || !colorsEqual(old.backgroundColor(), newStyle.backgroundColor())
                    || !shadowsEqual(old.boxShadow(), newStyle.boxShadow());
            if (likelyInteractive) {
                duration = 150L;
            }
        }

        if (duration > 0L && old != null) {
            // NEVER override a gradient background with a solid interpolant —
            // that made project cards vanish during transform transitions.
            boolean gradient =
                    hasGradientBackground(old) || hasGradientBackground(newStyle);
            Color fromBg = gradient ? null : old.backgroundColor();
            Color toBg = gradient ? null : newStyle.backgroundColor();
            if (fromBg == null || toBg == null) {
                fromBg = null;
                toBg = null;
            }

            ComputedStyle.Transform fromTx = old.transform();
            ComputedStyle.Transform toTx = newStyle.transform();
            ComputedStyle.BoxShadow fromSh = old.boxShadow();
            ComputedStyle.BoxShadow toSh = newStyle.boxShadow();

            boolean bgChanged =
                    fromBg != null && toBg != null && !colorsEqual(fromBg, toBg);
            boolean txChanged = !fromTx.equals(toTx);
            boolean shChanged = !shadowsEqual(fromSh, toSh);

            if (bgChanged || txChanged || shChanged) {
                long now = System.currentTimeMillis();
                // Continue from mid-flight visuals on rapid hover in/out.
                if (anim != null && !anim.done(now)) {
                    if (bgChanged) fromBg = anim.background(now);
                    if (txChanged) fromTx = anim.transform(now);
                    if (shChanged) fromSh = anim.shadow(now);
                }
                this.anim = new Transition(
                        now,
                        duration,
                        fromBg,
                        toBg,
                        fromTx,
                        toTx,
                        fromSh,
                        toSh
                );
            }
            // If nothing changed, keep any in-flight anim running.
            // (Nulling it here caused cards to pop when the pointer moved
            // between a card and its child button.)
        } else if (anim != null && anim.done(System.currentTimeMillis())) {
            this.anim = null;
        }

        this.style = newStyle;
    }

    private static boolean hasGradientBackground(ComputedStyle s) {
        if (s == null) return false;
        String img = s.get("background-image", "");
        if (img.toLowerCase().contains("gradient")) return true;
        String bg = s.get("background", "");
        return bg.toLowerCase().contains("gradient");
    }

    private static boolean colorsEqual(Color a, Color b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.getRGB() == b.getRGB();
    }

    private static boolean shadowsEqual(ComputedStyle.BoxShadow a, ComputedStyle.BoxShadow b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Float.compare(a.offsetX(), b.offsetX()) == 0
                && Float.compare(a.offsetY(), b.offsetY()) == 0
                && Float.compare(a.blur(), b.blur()) == 0
                && colorsEqual(a.color(), b.color());
    }

    public abstract void layout(float availableWidth, float startX, float startY);

    public abstract void paint(Graphics2D g);

    public float getBorderBoxWidth() {
        return borderBoxWidth;
    }

    public float getBorderBoxHeight() {
        return borderBoxHeight;
    }

    public float totalHeight() {
        return style.marginTop()
                + borderBoxHeight
                + style.marginBottom();
    }

    /** True if this box or any descendant still has a running transition. */
    public boolean hasRunningAnimation(long nowMs) {
        if (anim != null && !anim.done(nowMs)) {
            return true;
        }
        for (Box child : children) {
            if (child.hasRunningAnimation(nowMs)) {
                return true;
            }
        }
        // Form controls / inline-blocks store their real box in blockContent
        if (this instanceof InlineBox inline
                && inline.isInlineBlock()
                && inline.blockContent() != null) {
            return inline.blockContent().hasRunningAnimation(nowMs);
        }
        return false;
    }
}
