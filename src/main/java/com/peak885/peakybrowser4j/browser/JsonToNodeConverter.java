package com.peak885.peakybrowser4j.browser;

import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import java.util.Map;

public class JsonToNodeConverter {
    public static void populateNodes(JsonObject json, Node parent) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            Node child = new Node();
            child.type = Node.Type.ELEMENT;
            child.tag = entry.getKey();

            if (entry.getValue() instanceof JsonObject) {
                populateNodes((JsonObject) entry.getValue(), child);
            } else if (entry.getValue() instanceof JsonPrimitive) {
                child.text = entry.getValue().toString();
            }
            parent.children.add(child);
        }
    }
}