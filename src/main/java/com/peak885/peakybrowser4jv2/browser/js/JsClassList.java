package com.peak885.peakybrowser4jv2.browser.js;

import org.jsoup.nodes.Element;

public final class JsClassList {

    private final Element element;
    private final Runnable invalidator;

    JsClassList(
            Element element,
            Runnable invalidator
    ) {
        this.element = element;
        this.invalidator = invalidator;
    }

    public int getLength() {
        return element.classNames().size();
    }

    public String item(int index) {
        if (index < 0
                || index >= getLength()) {
            return null;
        }

        return element.classNames()
                .toArray(new String[0])[index];
    }

    public void add(String... classes) {
        if (classes == null) {
            return;
        }

        boolean modified = false;

        for (String value : classes) {
            if (value == null || value.isBlank()) {
                continue;
            }

            if (!element.hasClass(value)) {
                element.addClass(value);
                modified = true;
            }
        }

        if (modified) {
            invalidator.run();
        }
    }

    public void remove(String... classes) {
        if (classes == null) {
            return;
        }

        boolean modified = false;

        for (String value : classes) {
            if (value == null || value.isBlank()) {
                continue;
            }

            if (element.hasClass(value)) {
                element.removeClass(value);
                modified = true;
            }
        }

        if (modified) {
            invalidator.run();
        }
    }

    public boolean contains(String value) {
        return value != null
                && element.hasClass(value);
    }

    public boolean toggle(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        boolean present =
                element.hasClass(value);

        if (present) {
            element.removeClass(value);
        } else {
            element.addClass(value);
        }

        invalidator.run();

        return !present;
    }

    public void replace(
            String oldClass,
            String newClass
    ) {
        if (oldClass == null
                || newClass == null
                || oldClass.isBlank()
                || newClass.isBlank()) {
            return;
        }

        if (!element.hasClass(oldClass)) {
            return;
        }

        element.removeClass(oldClass);
        element.addClass(newClass);

        invalidator.run();
    }

    @Override
    public String toString() {
        return element.className();
    }
}