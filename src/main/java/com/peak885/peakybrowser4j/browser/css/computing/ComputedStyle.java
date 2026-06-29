package com.peak885.peakybrowser4j.browser.css.computing;

import com.peak885.peakybrowser4j.browser.DisplayType;
import com.peak885.peakybrowser4j.browser.styling.BoxSizing;

import java.awt.*;

public class ComputedStyle {

    // Layout
    public DisplayType display = DisplayType.BLOCK;

    public BoxSizing boxSizing = BoxSizing.CONTENT_BOX;

    // -1 = auto
    public float width = -1;
    public float height = -1;

    public float minWidth = 0;
    public float minHeight = 0;

    public float maxWidth = Float.MAX_VALUE;
    public float maxHeight = Float.MAX_VALUE;

    // Margin
    public float marginTop = 0;
    public float marginRight = 0;
    public float marginBottom = 0;
    public float marginLeft = 0;

    // Padding
    public float paddingTop = 0;
    public float paddingRight = 0;
    public float paddingBottom = 0;
    public float paddingLeft = 0;

    // Border (future)
    public float borderTop = 0;
    public float borderRight = 0;
    public float borderBottom = 0;
    public float borderLeft = 0;

    public DisplayType outerDisplay = DisplayType.BLOCK;

    // A more efficient way to store spacing
    public float[] margins = new float[4]; // [top, right, bottom, left]
    public float[] paddings = new float[4];

    // Typography
    public String fontFamily = "Segoe UI";
    public float fontSize = -1;
    public float[] textColor = null;

    public float[] backgroundColor = null;
}