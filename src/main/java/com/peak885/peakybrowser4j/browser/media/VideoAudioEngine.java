package com.peak885.peakybrowser4j.browser.media;

import org.bytedeco.javacv.Frame;
import org.tinylog.Logger;

import javax.sound.sampled.*;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class VideoAudioEngine {

    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>(256);

    private volatile boolean running;
    private Thread thread;

    private SourceDataLine line;
    private byte[] pcmBuffer;
    private long totalBytesWritten = 0;
    private AudioFormat currentFormat;

    public void start() {
        running = true;
        thread = new Thread(this::playbackLoop, "VideoAudio");
        thread.start();
    }

    public void stop() {
        running = false;

        if (thread != null) {
            thread.interrupt();
        }

        if (line != null) {
            line.drain();
            line.stop();
            line.close();
        }

        queue.clear();
    }

    public void submit(Frame frame) {
        if (!running || frame == null)
            return;

        if (frame.samples == null)
            return;

        queue.offer(frame.clone());
    }

    private void playbackLoop() {

        try {

            while (running) {

                Frame frame = queue.take();

                if (line == null) {
                    setupLine(frame);
                }

                writeFrame(frame);
            }

        } catch (InterruptedException ignored) {

        } catch (Exception e) {
            Logger.error(e, "Audio playback failed");
        }
    }

    private void setupLine(Frame frame) throws Exception {

        AudioFormat format = new AudioFormat(
                frame.sampleRate,
                16,
                frame.audioChannels,
                true,
                false
        );

        currentFormat = new AudioFormat(
                frame.sampleRate, 16, frame.audioChannels, true, false
        );
        line = AudioSystem.getSourceDataLine(currentFormat);
        line.open(currentFormat);
        line.start();

        Logger.info("Audio {} Hz {} channels",
                frame.sampleRate,
                frame.audioChannels);
    }

    private void writeFrame(Frame frame) {

        Buffer[] buffers = frame.samples;

        if (buffers == null || buffers.length == 0)
            return;

        if (!(buffers[0] instanceof ShortBuffer))
            return;

        int channels = buffers.length;

        ShortBuffer[] channelBuffers = new ShortBuffer[channels];

        for (int i = 0; i < channels; i++) {
            channelBuffers[i] = ((ShortBuffer) buffers[i]).duplicate();
        }

        int samples = channelBuffers[0].remaining();

        int required = samples * channels * 2;

        if (pcmBuffer == null || pcmBuffer.length < required) {
            pcmBuffer = new byte[required];
        }

        int index = 0;

        for (int s = 0; s < samples; s++) {

            for (ShortBuffer b : channelBuffers) {

                short sample = b.get();

                pcmBuffer[index++] = (byte) sample;
                pcmBuffer[index++] = (byte) (sample >> 8);
            }
        }

        line.write(pcmBuffer, 0, index);

        synchronized(this) {
            totalBytesWritten += index;
        }
    }

    public long getCurrentTimestamp() {
        if (currentFormat == null) return 0;

        // bytes / (bytes per frame * frames per second)
        int bytesPerFrame = currentFormat.getFrameSize();
        float sampleRate = currentFormat.getSampleRate();

        return (long) ((totalBytesWritten / (double) bytesPerFrame) / sampleRate * 1_000_000);
    }

    public void pause() {
        running = false;
        if (line != null) {
            line.stop();
        }
    }

    public void resume() {
        running = true;
        if (line != null) {
            line.start();
        }
    }
}