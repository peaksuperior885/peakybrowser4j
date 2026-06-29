package com.peak885.peakybrowser4j;

import com.peak885.peakybrowser4j.browser.engine.BrowserEngine;

public class Main {
    public static void main(String[] args) {
        String url = (args.length > 0)
                ? args[0]
                : "https://peaksuperior885.github.io/testmp4/";
                // test video : "https://peaksuperior885.github.io/testmp4/";

        new BrowserEngine().run(url);
    }
}