package com.peak885.peakybrowser4jv2.browser.image;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.Icon;

public class IconUtils {
    public static Image iconToImage(Icon icon) {
        BufferedImage image = new BufferedImage(
                icon.getIconWidth(),
                icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        icon.paintIcon(null, g2d, 0, 0);
        g2d.dispose();
        return image;
    }
}