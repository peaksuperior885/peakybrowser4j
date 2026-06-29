package com.peak885.peakybrowser4j.browser.bridge;

import com.peak885.peakybrowser4j.browser.css.computing.ComputedStyle;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Arrays;

public class StyleBridge extends ScriptableObject {
    private ComputedStyle style;
    public StyleBridge(ComputedStyle style) { this.style = style; }

    @Override
    public String getClassName() { return "StyleBridge"; }

    @Override
    public Object get(String name, Scriptable start) {
        if ("backgroundColor".equals(name)) return Arrays.toString(style.backgroundColor);
        if ("fontSize".equals(name)) return style.fontSize;
        if ("color".equals(name)) return Arrays.toString(style.textColor);
        return super.get(name, start);
    }
}