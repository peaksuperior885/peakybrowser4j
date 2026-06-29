package com.peak885.peakybrowser4j.browser;

import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import com.peak885.peakybrowser4j.browser.css.computing.ComputedStyle;
import com.peak885.peakybrowser4j.browser.image.ImageScaleMode;
import org.lwjgl.nanovg.NVGColor;

import java.util.*;

public class Node {

    public enum Type {
        ELEMENT, TEXT, IMAGE
    }

    public String text;
    public String imageUrl;
    public int imageWidth;
    public int imageHeight;
    public String altText;

    public String id;
    public String tag;
    public String href;

    public Type type;

    public List<Node> children = new ArrayList<>();
    public Node parent;

    public float x, y, width, height;

    public Set<String> classes = new HashSet<>();
    public boolean isLink = false;

    public ImageScaleMode imageScaleMode = ImageScaleMode.STRETCH;

    public DisplayType display = DisplayType.BLOCK;

    public ComputedStyle computedStyle = new ComputedStyle();
    public float[] textColor = new float[]{1,1,1,1};
    public float[] backgroundColor = null;

    public float marginTop, marginBottom;
    public float paddingLeft, paddingRight, paddingTop, paddingBottom;
    public float fontSize;

    public boolean isHovered = false;
    public boolean isPressed = false;
    public boolean isFocused = false;
    public Map<String, String> attributes = new HashMap<>();
    public static final NVGColor SHARED_COLOR = NVGColor.create();
    public float borderRadius = 0f;
    public float shadowBlur = 0f;
    public float[] shadowColor = {0, 0, 0, 0.5f}; // RGBA
    public float borderWidth = 0f;
    public NVGColor borderColor = NVGColor.create(); // Or handle as float[]
    public String videoUrl;
    public boolean isVideoNode = false;
    public float intrinsicWidth;
    public float intrinsicHeight;

    public boolean isDirty = false;

    public void markDirty() {
        this.isDirty = true;
        if (parent != null) {
            parent.markDirty();
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("tag", new JsonPrimitive(tag));
        json.put("text", new JsonPrimitive(text != null ? text : ""));

        // Add children as an array
        blue.endless.jankson.JsonArray childrenArray = new blue.endless.jankson.JsonArray();
        for (Node child : children) {
            childrenArray.add(child.toJson());
        }
        json.put("children", childrenArray);
        return json;
    }
}