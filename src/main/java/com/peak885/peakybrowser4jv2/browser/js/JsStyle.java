package com.peak885.peakybrowser4jv2.browser.js;

import org.jsoup.nodes.Element;

public final class JsStyle {

    private final Element element;
    private final Runnable invalidator;

    JsStyle(
            Element element,
            Runnable invalidator
    ) {
        this.element = element;
        this.invalidator = invalidator;
    }

    public String getPropertyValue(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        String wanted = normalize(name);

        String style = element.attr("style");

        if (style == null || style.isBlank()) {
            return "";
        }

        for (String declaration :
                style.split(";")) {

            int colon = declaration.indexOf(':');

            if (colon < 0) {
                continue;
            }

            String property =
                    normalize(
                            declaration.substring(
                                    0,
                                    colon
                            )
                    );

            if (property.equals(wanted)) {
                return declaration
                        .substring(colon + 1)
                        .trim();
            }
        }

        return "";
    }

    public void setProperty(
            String name,
            String value
    ) {
        if (name == null || name.isBlank()) {
            return;
        }

        String property = normalize(name);

        StringBuilder result =
                new StringBuilder();

        boolean replaced = false;

        String existing = element.attr("style");

        if (existing != null
                && !existing.isBlank()) {

            for (String declaration :
                    existing.split(";")) {

                int colon =
                        declaration.indexOf(':');

                if (colon < 0) {
                    continue;
                }

                String oldProperty =
                        normalize(
                                declaration.substring(
                                        0,
                                        colon
                                )
                        );

                if (oldProperty.equals(property)) {

                    if (!replaced
                            && value != null) {

                        result.append(property)
                                .append(':')
                                .append(value)
                                .append(';');

                        replaced = true;
                    }

                    continue;
                }

                result.append(
                        declaration.trim()
                ).append(';');
            }
        }

        if (!replaced && value != null) {
            result.append(property)
                    .append(':')
                    .append(value)
                    .append(';');
        }

        if (result.isEmpty()) {
            element.removeAttr("style");
        } else {
            element.attr(
                    "style",
                    result.toString()
            );
        }

        invalidator.run();
    }

    public void removeProperty(String name) {
        setProperty(name, null);
    }

    public String getCssText() {
        return element.attr("style");
    }

    public void setCssText(String cssText) {
        if (cssText == null || cssText.isBlank()) {
            element.removeAttr("style");
        } else {
            element.attr("style", cssText);
        }

        invalidator.run();
    }

    public void setDisplay(String value) {
        setProperty("display", value);
    }

    public void setVisibility(String value) {
        setProperty("visibility", value);
    }

    public void setColor(String value) {
        setProperty("color", value);
    }

    public void setBackgroundColor(String value) {
        setProperty("background-color", value);
    }

    public void setWidth(String value) {
        setProperty("width", value);
    }

    public void setHeight(String value) {
        setProperty("height", value);
    }

    public void setPosition(String value) {
        setProperty("position", value);
    }

    public void setTop(String value) {
        setProperty("top", value);
    }

    public void setLeft(String value) {
        setProperty("left", value);
    }

    public void setRight(String value) {
        setProperty("right", value);
    }

    public void setBottom(String value) {
        setProperty("bottom", value);
    }

    public void setMargin(String value) {
        setProperty("margin", value);
    }

    public void setPadding(String value) {
        setProperty("padding", value);
    }

    private static String normalize(String value) {
        return value
                .trim()
                .toLowerCase()
                .replace('_', '-');
    }
}