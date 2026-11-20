package com.peak885.peakybrowser4jv2.browser.js;

import org.jsoup.nodes.Element;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

/** Small Java2D-backed canvas surface for pages that need basic 2D drawing. */
public final class CanvasRenderingContext2D {
    private static final Map<Element, CanvasRenderingContext2D> SURFACES = new WeakHashMap<>();
    private static final int MAX_DIMENSION = 4096;
    private Runnable invalidator;
    private final ArrayDeque<CanvasState> saved = new ArrayDeque<>();
    private BufferedImage image;
    private Graphics2D graphics;
    private int width, height;
    private Object fillStyle = "#000000", strokeStyle = "#000000";
    private float globalAlpha = 1f, lineWidth = 1f;
    private String font = "10px sans-serif";
    private Path2D.Double path = new Path2D.Double();

    public static synchronized CanvasRenderingContext2D forElement(Element element, Runnable invalidator) {
        CanvasRenderingContext2D surface = SURFACES.computeIfAbsent(element, e -> new CanvasRenderingContext2D(
                dimension(e, "width", 300), dimension(e, "height", 150), invalidator));
        if (invalidator != null) surface.invalidator = invalidator;
        return surface;
    }
    private static int dimension(Element e, String name, int fallback) {
        try { return Math.max(0, Math.min(MAX_DIMENSION, Integer.parseInt(e.attr(name)))); }
        catch (RuntimeException ex) { return fallback; }
    }
    private CanvasRenderingContext2D(int w, int h, Runnable invalidator) {
        this.invalidator = invalidator == null ? () -> {} : invalidator;
        resize(w, h);
    }
    public synchronized Object getContext(String type) {
        return "2d".equalsIgnoreCase(type) ? this : null;
    }
    public synchronized int getWidth() { return width; }
    public synchronized void setWidth(int value) { resize(value, height); }
    public synchronized int getHeight() { return height; }
    public synchronized void setHeight(int value) { resize(width, value); }
    private void resize(int w, int h) {
        width = Math.max(0, Math.min(MAX_DIMENSION, w));
        height = Math.max(0, Math.min(MAX_DIMENSION, h));

        if (graphics != null) {
            graphics.dispose();
        }

        for (CanvasState state : saved) {
            state.graphics.dispose();
        }
        saved.clear();

        image = new BufferedImage(
                Math.max(1, width),
                Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB
        );

        graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        path = new Path2D.Double();
        changed();
    }
    public synchronized BufferedImage image() { return image; }
    public synchronized Object getFillStyle() { return fillStyle; }
    public synchronized void setFillStyle(Object value) { fillStyle = value; }
    public synchronized Object getStrokeStyle() { return strokeStyle; }
    public synchronized void setStrokeStyle(Object value) { strokeStyle = value; }
    public synchronized float getGlobalAlpha() { return globalAlpha; }
    public synchronized void setGlobalAlpha(double value) { globalAlpha = (float)Math.max(0, Math.min(1, value)); }
    public synchronized float getLineWidth() { return lineWidth; }
    public synchronized void setLineWidth(double value) { if (value > 0 && Double.isFinite(value)) lineWidth = (float)value; }
    public synchronized String getFont() { return font; }
    public synchronized void setFont(String value) {
        if (value == null || value.isBlank()) return;
        font = value;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)px(?:\\s+([^,]+))?").matcher(value);
        if (m.find()) {
            float size = Float.parseFloat(m.group(1));
            String family = m.group(2) == null ? "SansSerif" : m.group(2).replaceAll("['\"]", "").trim();
            graphics.setFont(new Font(family, Font.PLAIN, Math.max(1, Math.round(size))));
        }
    }
    public synchronized void beginPath() { path = new Path2D.Double(); }
    public synchronized void moveTo(double x,double y) { path.moveTo(x,y); }
    public synchronized void lineTo(double x,double y) { path.lineTo(x,y); }
    public synchronized void closePath() { path.closePath(); }
    public synchronized void stroke() { apply(strokeStyle); graphics.setStroke(new BasicStroke(lineWidth)); graphics.draw(path); changed(); }
    public synchronized void fill() { apply(fillStyle); graphics.fill(path); changed(); }
    public synchronized void fillRect(double x,double y,double w,double h) { apply(fillStyle); graphics.fill(new java.awt.geom.Rectangle2D.Double(x,y,w,h)); changed(); }
    public synchronized void strokeRect(double x,double y,double w,double h) { apply(strokeStyle); graphics.setStroke(new BasicStroke(lineWidth)); graphics.draw(new java.awt.geom.Rectangle2D.Double(x,y,w,h)); changed(); }
    public synchronized void clearRect(double x,double y,double w,double h) { Composite old=graphics.getComposite(); graphics.setComposite(AlphaComposite.Clear); graphics.fill(new java.awt.geom.Rectangle2D.Double(x,y,w,h)); graphics.setComposite(old); changed(); }
    public synchronized void arc(double x,double y,double radius,double start,double end,boolean anticlockwise) {
        double extent=Math.toDegrees(end-start); if(anticlockwise && extent>0) extent-=360; if(!anticlockwise && extent<0) extent+=360;
        path.append(new Arc2D.Double(x-radius,y-radius,radius*2,radius*2,-Math.toDegrees(start),-extent,Arc2D.OPEN),true);
    }
    public synchronized void save() {
        saved.push(new CanvasState(this));
    }

    public synchronized void restore() {
        if (saved.isEmpty()) {
            return;
        }

        CanvasState state = saved.pop();

        graphics.dispose();
        graphics = state.graphics;

        fillStyle = state.fillStyle;
        strokeStyle = state.strokeStyle;
        globalAlpha = state.globalAlpha;
        lineWidth = state.lineWidth;
        font = state.font;
    }
    public synchronized void translate(double x,double y) { graphics.translate(x,y); }
    public synchronized void rotate(double angle) { graphics.rotate(angle); }
    public synchronized void scale(double x,double y) { graphics.scale(x,y); }
    public synchronized void drawImage(Object source,double x,double y) { BufferedImage b=toImage(source); if(b!=null){graphics.drawImage(b,(int)Math.round(x),(int)Math.round(y),null);changed();} }
    public synchronized void drawImage(Object source,double x,double y,double w,double h) { BufferedImage b=toImage(source); if(b!=null){graphics.drawImage(b,(int)x,(int)y,(int)w,(int)h,null);changed();} }
    private BufferedImage toImage(Object value) {
        if (value instanceof BufferedImage b) return b;
        if (value instanceof CanvasRenderingContext2D c) return c.image();

        if (value instanceof org.mozilla.javascript.Scriptable scriptable) {
            Object nativeImg = org.mozilla.javascript.ScriptableObject.getProperty(scriptable, "_javaImage");
            if (nativeImg instanceof BufferedImage b) {
                return b;
            }
        }
        return null;
    }
    public synchronized Object measureText(String text) { return new TextMetrics(graphics.getFontMetrics().stringWidth(text == null ? "" : text)); }
    public synchronized void fillText(String text,double x,double y) { apply(fillStyle); graphics.drawString(text == null ? "" : text,(float)x,(float)y);changed(); }
    public synchronized void fillText(String text,double x,double y,double maxWidth) { fillText(text,x,y); }
    public synchronized void strokeText(String text,double x,double y) { apply(strokeStyle); graphics.setStroke(new BasicStroke(lineWidth)); graphics.drawString(text == null ? "" : text,(float)x,(float)y);changed(); }
    private void apply(Object style) { graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,globalAlpha)); graphics.setColor(parseColor(style)); }
    private static Color parseColor(Object v) {
        if(v instanceof Color c)return c;
        String s=String.valueOf(v).trim().toLowerCase();
        try { if(s.startsWith("#")){String h=s.substring(1); if(h.length()==3)return new Color(Integer.parseInt(""+h.charAt(0)+h.charAt(0)+h.charAt(1)+h.charAt(1)+h.charAt(2)+h.charAt(2),16)); if(h.length()==6)return new Color(Integer.parseInt(h,16)); if(h.length()==8)return new Color((int)Long.parseLong(h,16),true);}
            if(s.startsWith("rgb")){String[] p=s.substring(s.indexOf('(')+1,s.lastIndexOf(')')).split("[,/]" );int a=p.length>3?(int)(Double.parseDouble(p[3].trim())*255):255;return new Color(Integer.parseInt(p[0].trim()),Integer.parseInt(p[1].trim()),Integer.parseInt(p[2].trim()),Math.max(0,Math.min(255,a)));}
            return switch(s){case "white"->Color.WHITE;case "red"->Color.RED;case "green"->Color.GREEN;case "blue"->Color.BLUE;case "transparent"->new Color(0,0,0,0);default->Color.BLACK;};
        } catch(Exception ignored){return Color.BLACK;}
    }
    private void changed(){invalidator.run();}
    public static final class TextMetrics { private final double width; TextMetrics(double width){this.width=width;} public double getWidth(){return width;}}
    private static final class CanvasState {
        private final Graphics2D graphics;
        private final Object fillStyle;
        private final Object strokeStyle;
        private final float globalAlpha;
        private final float lineWidth;
        private final String font;

        private CanvasState(CanvasRenderingContext2D context) {
            this.graphics = (Graphics2D) context.graphics.create();
            this.fillStyle = context.fillStyle;
            this.strokeStyle = context.strokeStyle;
            this.globalAlpha = context.globalAlpha;
            this.lineWidth = context.lineWidth;
            this.font = context.font;
        }
    }
}
