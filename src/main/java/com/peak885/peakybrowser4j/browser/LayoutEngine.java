package com.peak885.peakybrowser4j.browser;

import com.peak885.peakybrowser4j.browser.css.computing.ComputedStyle;
import com.peak885.peakybrowser4j.browser.styling.BoxSizing;

public class LayoutEngine {

    public void computeLayout(Node root, float startX, float startY, float availableWidth) {
        if (root == null) return;

        root.x = startX;
        root.y = startY;
        root.width = availableWidth;

        layoutNode(root, startX, startY, availableWidth);
    }

    private float layoutNode(Node node, float x, float y, float availableWidth) {
        ComputedStyle style = node.computedStyle != null ? node.computedStyle : new ComputedStyle();

        // 1. Calculate base horizontal/vertical space occupied by borders and padding
        float horizontalFrame = style.borderLeft + style.borderRight + style.paddingLeft + style.paddingRight;
        float verticalFrame = style.borderTop + style.borderBottom + style.paddingTop + style.paddingBottom;

        // 2. Width Calculation Logic
        float totalAvailableForMargins = availableWidth;
        float contentWidth;
        float widthWithMargins = totalAvailableForMargins - style.marginLeft - style.marginRight;

        if (style.boxSizing == BoxSizing.BORDER_BOX) {
            // BORDER_BOX: Width includes padding and border.
            // We ensure the node doesn't exceed the available width.
            node.width = Math.max(0, widthWithMargins);
            // Content width is what's left after subtracting the frame
            contentWidth = Math.max(0, node.width - horizontalFrame);
        } else {
            // CONTENT_BOX (Default): Width is only the content.
            // Total width = content + border + padding + margins
            node.width = Math.max(0, widthWithMargins);
            contentWidth = Math.max(0, node.width - horizontalFrame);
        }

        if (node.isVideoNode) {
            float naturalW = (node.intrinsicWidth > 0) ? node.intrinsicWidth : 640f;
            float naturalH = (node.intrinsicHeight > 0) ? node.intrinsicHeight : 360f;
            float aspect = naturalW / naturalH;

            float targetWidth = (style.width > 0) ? style.width : availableWidth;
            node.width = Math.min(targetWidth, availableWidth);

            if (style.height > 0) {
                node.height = style.height;
            } else {
                node.height = node.width / aspect;
            }

            return node.height;
        }


        // 3. Position and Child Layout
        node.x = x + style.marginLeft;
        node.y = y + style.marginTop;

        boolean isRoot = (node.parent == null);

        if (isRoot) {
            node.x = x;
            node.y = y;
        } else {
            node.x = x + style.marginLeft;
            node.y = y + style.marginTop;
        }

        float childContainerX = node.x + style.borderLeft + style.paddingLeft;
        float cursorY = node.y + style.borderTop + style.paddingTop;

        for (Node child : node.children) {
            float childHeight = layoutNode(child, childContainerX, cursorY, contentWidth);
            ComputedStyle childStyle = child.computedStyle != null ? child.computedStyle : new ComputedStyle();
            cursorY += childHeight + childStyle.marginTop + childStyle.marginBottom;
        }

        // 4. Height Calculation
        if (node.children.isEmpty()) {
            node.height = Math.max(verticalFrame + 20, style.minHeight);
        } else {
            node.height = Math.max((cursorY - node.y) + style.paddingBottom + style.borderBottom, style.minHeight);
        }

        if (node.parent == null) {
            node.x = 0;
            node.y = 0;
            node.width = availableWidth; // The 'w[0]' passed from BrowserEngine
            // Get the actual window height here if possible,
            // or just use a passed-in availableHeight
            node.height = 800; // Force height or pass it as a parameter

            // Layout children normally
            for (Node child : node.children) {
                layoutNode(child, 0, 0, node.width);
            }
            return node.height;
        }

        return node.height;
    }
}