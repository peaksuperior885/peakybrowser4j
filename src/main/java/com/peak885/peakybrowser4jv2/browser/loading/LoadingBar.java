package com.peak885.peakybrowser4jv2.browser.loading;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ultra-smooth animated loading bar with a live rainbow gradient.
 * Works beautifully with FlatLaf.
 */
public final class LoadingBar extends JPanel {

    private static final int BAR_HEIGHT = 3;
    private static final float ANIMATION_SPEED = 0.015f;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private float hueOffset = 0f;
    private int progress = 0;          // 0-100
    private boolean indeterminate = true;
    private Timer animator;
    private Timer hideTimer;

    public LoadingBar() {
        setOpaque(false);
        setPreferredSize(new Dimension(0, BAR_HEIGHT));
        setVisible(false);

        // Smooth 60fps animation (16ms)
        animator = new Timer(16, e -> {
            if (!running.get()) return;
            hueOffset = (hueOffset + ANIMATION_SPEED) % 1.0f;
            repaint();
        });
        animator.setCoalesce(true);

        // Track-hiding timer to prevent overlapping triggers
        hideTimer = new Timer(280, e -> stop());
        hideTimer.setRepeats(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!isVisible()) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int w = getWidth();
        int h = getHeight();

        // Soft rounded track
        g2.setColor(new Color(255, 255, 255, 18));
        g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));

        if (indeterminate) {
            // Sliding indeterminate pill track for active movement
            int pillWidth = Math.max(40, w / 4);
            float positionFactor = (hueOffset * 2.5f) % 1.0f;
            int xPos = (int) ((w + pillWidth) * positionFactor) - pillWidth;
            paintRainbow(g2, xPos, pillWidth, h);
        } else {
            // Determinate progress fill
            int fillWidth = (int) (w * (progress / 100.0));
            if (fillWidth > 0) {
                paintRainbow(g2, 0, fillWidth, h);
            }
        }

        g2.dispose();
    }

    private void paintRainbow(Graphics2D g2, int x, int width, int height) {
        float[] fractions = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
        Color[] colors = new Color[6];

        for (int i = 0; i < 6; i++) {
            float hue = (hueOffset + (i * 0.15f)) % 1.0f;
            colors[i] = Color.getHSBColor(hue, 0.85f, 1.0f);
        }

        // Clip painting bounds to prevent overflow outside the rounded track container
        g2.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), height, height, height));

        LinearGradientPaint gradient = new LinearGradientPaint(
                x, 0, x + width, 0,
                fractions, colors
        );

        g2.setPaint(gradient);
        g2.fill(new RoundRectangle2D.Float(x, 0, width, height, height, height));

        // Soft specular highlight overlay
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g2.setPaint(new GradientPaint(0, 0, Color.WHITE, 0, height, new Color(255, 255, 255, 0)));
        g2.fill(new RoundRectangle2D.Float(x, 0, width, height * 0.5f, height, height));
    }

    public void start() {
        SwingUtilities.invokeLater(() -> {
            if (hideTimer.isRunning()) hideTimer.stop();
            indeterminate = true;
            progress = 0;
            running.set(true);
            setVisible(true);
            if (!animator.isRunning()) {
                animator.start();
            }
        });
    }

    public void setProgress(int value) {
        SwingUtilities.invokeLater(() -> {
            if (hideTimer.isRunning()) hideTimer.stop();
            indeterminate = false;
            progress = Math.max(0, Math.min(100, value));
            running.set(true);
            setVisible(true);
            if (!animator.isRunning()) {
                animator.start();
            }
            repaint();
        });
    }

    public void finish() {
        SwingUtilities.invokeLater(() -> {
            if (hideTimer.isRunning()) hideTimer.stop();
            indeterminate = false;
            progress = 100;
            repaint();
            hideTimer.restart();
        });
    }

    public void stop() {
        SwingUtilities.invokeLater(() -> {
            if (hideTimer.isRunning()) hideTimer.stop();
            running.set(false);
            if (animator != null) animator.stop();
            setVisible(false);
            progress = 0;
            indeterminate = true;
        });
    }

    public void dispose() {
        stop();
        if (animator != null) {
            animator.stop();
            animator = null;
        }
        if (hideTimer != null) {
            hideTimer.stop();
            hideTimer = null;
        }
    }
}