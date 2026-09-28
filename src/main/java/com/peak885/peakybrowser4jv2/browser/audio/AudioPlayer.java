package com.peak885.peakybrowser4jv2.browser.audio;

import javazoom.jl.player.Player;

import java.io.InputStream;
import java.net.URL;

public final class AudioPlayer {

    private AudioPlayer() {
    }

//    public static void play(String url) {
//        Thread.startVirtualThread(() -> {
//            try (InputStream input = new URL(url).openStream()) {
//                Player player = new Player(input);
//                player.play();
//            } catch (Exception e) {
//                System.err.println("Failed to play audio: " + url);
//                e.printStackTrace();
//            }
//        });
//    }
}