package com.peak885.peakybrowser4j.browser.media;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;
import org.tinylog.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.peak885.peakybrowser4j.browser.Renderer.convertFrameToRGBA;

public final class VideoEngine {

    private static class State {
        FFmpegFrameGrabber grabber;
        VideoAudioEngine audio;

        AtomicReference<VideoFrame> latestFrame = new AtomicReference<>();

        volatile boolean playing = true;
        volatile long seekTargetUs = -1;
        volatile boolean eof = false;

        long durationUs = 0;
        long currentUs = 0;
    }

    private static final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    // ---------------- PUBLIC API ----------------

    public static boolean isPlaying(String url) {
        State s = states.get(url);
        return s != null && s.playing;
    }

    public static void togglePlayPause(String url) {
        State s = states.get(url);
        if (s == null) return;

        s.playing = !s.playing;

        if (s.audio != null) {
            if (s.playing) s.audio.resume();
            else s.audio.pause();
        }
    }

    public static float getCurrentTime(String url) {
        State s = states.get(url);
        if (s == null) return 0;
        return s.currentUs / 1_000_000f;
    }

    public static float getDuration(String url) {
        State s = states.get(url);
        if (s == null) return 0;
        return s.durationUs / 1_000_000f;
    }

    public static void seek(String url, float seconds) {
        State s = states.get(url);
        if (s == null) return;

        long us = (long) (seconds * 1_000_000);
        s.seekTargetUs = us;
        s.eof = false;
    }

    public static VideoFrame grabLatestFrame(String url) {
        State s = states.get(url);
        if (s == null) return null;
        return s.latestFrame.getAndSet(null);
    }

    public static void startVideo(String url) {
        if (states.containsKey(url)) return;

        State s = new State();
        states.put(url, s);

        Thread t = new Thread(() -> decodeLoop(url, s), "video-decoder-" + url);
        t.setDaemon(true);
        t.start();
    }

    // ---------------- CORE LOOP ----------------

    private static void decodeLoop(String url, State s) {
        try {
            openGrabber(url, s);

            s.audio = new VideoAudioEngine();
            s.audio.start();

            while (true) {

                // pause handling
                if (!s.playing) {
                    Thread.sleep(10);
                    continue;
                }

                // SEEK HANDLING (critical fix)
                if (s.seekTargetUs >= 0) {
                    long target = s.seekTargetUs;
                    s.seekTargetUs = -1;

                    s.grabber.setTimestamp(target);
                    s.grabber.flush();
                    s.eof = false;
                }

                Frame frame = s.grabber.grab();

                // EOF HANDLING (THIS was your main bug)
                if (frame == null) {
                    s.eof = true;

                    // instead of dying, just wait for seek or retry
                    Thread.sleep(10);

                    // optional: restart if needed
                    if (s.seekTargetUs >= 0) continue;

                    continue;
                }

                long ts = s.grabber.getTimestamp();
                s.currentUs = Math.max(s.currentUs, ts);

                if (frame.image != null) {
                    ByteBuffer rgba = convertFrameToRGBA(frame);
                    VideoFrame vf = new VideoFrame(rgba, frame.imageWidth, frame.imageHeight);

                    VideoFrame old = s.latestFrame.getAndSet(vf);
                    if (old != null) MemoryUtil.memFree(old.pixels());
                }

                if (frame.samples != null) {
                    s.audio.submit(frame);
                }
            }

        } catch (Exception e) {
            Logger.error(e);
        }
    }

    // ---------------- GRABBER SETUP ----------------

    private static void openGrabber(String url, State s) throws Exception {
        s.grabber = new FFmpegFrameGrabber(url);
        s.grabber.start();

        s.durationUs = s.grabber.getLengthInTime();
        s.currentUs = 0;
    }
}