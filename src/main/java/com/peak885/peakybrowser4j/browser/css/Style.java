package com.peak885.peakybrowser4j.browser.css;

import java.util.HashMap;
import java.util.Map;

public class Style {
    public Map<String, String> properties = new HashMap<>();

    public void set(String key, String value) {
        properties.put(key, value);
    }

    public String get(String key) {
        return properties.getOrDefault(key, "");
    }
}