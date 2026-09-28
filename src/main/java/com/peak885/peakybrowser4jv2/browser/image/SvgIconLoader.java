package com.peak885.peakybrowser4jv2.browser.image;

import com.kitfox.svg.SVGDiagram;
import com.kitfox.svg.SVGUniverse;
import org.tinylog.Logger;

import javax.swing.*;
import java.awt.*;
import java.net.URL;

public final class SvgIconLoader {

    private static final SVGUniverse SVG_UNIVERSE = new SVGUniverse();

    private SvgIconLoader() {}

    public static Icon load(String resourcePath) {
        return load(resourcePath, 20);
    }

    public static Icon load(String resourcePath, int size) {
        try {
            URL url = SvgIconLoader.class.getResource(resourcePath);
            if (url == null && resourcePath.startsWith("/")) {
                url = SvgIconLoader.class.getResource(resourcePath.substring(1));
            }
            if (url == null) {
                Logger.error("SVG resource not found on classpath: {}", resourcePath);
                return new EmptyIcon(size);
            }

            var uri = SVG_UNIVERSE.loadSVG(url);
            SVGDiagram diagram = SVG_UNIVERSE.getDiagram(uri);

            if (diagram == null) {
                Logger.error("SVG Salamander produced null diagram for {}", resourcePath);
                return new EmptyIcon(size);
            }

            return new VectorSvgIcon(diagram, size);

        } catch (Exception e) {
            Logger.error(e, "Failed to load SVG icon: {}", resourcePath);
            return new EmptyIcon(size);
        }
    }

    private static class VectorSvgIcon implements Icon {
        private final SVGDiagram diagram;
        private final int size;

        public VectorSvgIcon(SVGDiagram diagram, int size) {
            this.diagram = diagram;
            this.size = size;
            this.diagram.setIgnoringClipHeuristic(true);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g2d.translate(x, y);

            float diagWidth = diagram.getWidth();
            float diagHeight = diagram.getHeight();
            if (diagWidth > 0 && diagHeight > 0) {
                g2d.scale((double) size / diagWidth, (double) size / diagHeight);
            }

            try {
                diagram.render(g2d);
            } catch (Exception e) {
                Logger.error(e, "Error rendering vector SVG icon");
            }
            g2d.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static class EmptyIcon implements Icon {
        private final int size;
        public EmptyIcon(int size) { this.size = size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {}
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }
}