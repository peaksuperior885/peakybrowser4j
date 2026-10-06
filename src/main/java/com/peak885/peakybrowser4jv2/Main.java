package com.peak885.peakybrowser4jv2;

import com.peak885.peakybrowser4jv2.browser.BrowserApplication;

public final class Main {
    public static void main(String[] args) {
        BrowserApplication.start(
                //"internal:newtab"
                "https://www.google.com/search?q=example&gbv=1"
                //"https://www.google.com/webhp?gbv=1"
                //"https://peaksuperior885.github.io/downloads.html#rejei"
                //"https://www.curseforge.com/minecraft/mc-mods/blendin/download/8809095"
                //"http://acid2.acidtests.org/#top"
                //"https://www.whatismybrowser.com/"
                //"https://peaksuperior885.github.io/testmp4/"
        );
    }
}