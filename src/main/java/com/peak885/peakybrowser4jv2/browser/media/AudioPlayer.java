package com.peak885.peakybrowser4jv2.browser.media;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.AACDecoderConfig;
import net.sourceforge.jaad.aac.AACException;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.tinylog.Logger;

import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioPlayer {

    private AudioPlayer() {
    }

    public static void playMp4Aac(byte[] mp4Bytes, AtomicBoolean stop) {
        if (mp4Bytes == null || mp4Bytes.length == 0) {
            Logger.warn("[AUDIO] Empty MP4 data");
            return;
        }

        SeekableByteChannel channel = null;
        MP4Demuxer demuxer = null;
        SourceDataLine line = null;
        ByteArrayOutputStream pcmCollector = new ByteArrayOutputStream();

        try {
            channel = ByteBufferSeekableByteChannel.readFromByteBuffer(
                    ByteBuffer.wrap(mp4Bytes)
            );
            demuxer = MP4Demuxer.createMP4Demuxer(channel);

            List<DemuxerTrack> audioTracks = demuxer.getAudioTracks();
            if (audioTracks == null || audioTracks.isEmpty()) {
                Logger.info("[AUDIO] MP4 contains no audio track");
                return;
            }

            DemuxerTrack track = audioTracks.get(0);
            DemuxerTrackMeta meta = track.getMeta();
            if (meta == null || meta.getCodec() != Codec.AAC) {
                Logger.warn("[AUDIO] Unsupported or missing AAC codec metadata");
                return;
            }

            ByteBuffer codecPrivate = meta.getCodecPrivate();
            if (codecPrivate == null || !codecPrivate.hasRemaining()) {
                Logger.warn("[AUDIO] Missing AAC codecPrivate (AudioSpecificConfig)");
                return;
            }

            List<byte[]> rawPackets = new ArrayList<>();
            Packet packet;
            while ((packet = track.nextFrame()) != null) {
                ByteBuffer data = packet.getData();
                if (data != null && data.hasRemaining()) {
                    byte[] packetBytes = new byte[data.remaining()];
                    data.duplicate().get(packetBytes);
                    rawPackets.add(packetBytes);
                }
            }

            Logger.info("[AUDIO] Extracted {} raw AAC packets from MP4 track", rawPackets.size());
            if (rawPackets.isEmpty()) {
                Logger.warn("[AUDIO] Audio track contains zero packets!");
                return;
            }

            try { demuxer.close(); } catch (Exception ignored) {}
            try { channel.close(); } catch (Exception ignored) {}
            channel = null;
            demuxer = null;

            Decoder jaad = new Decoder(codecPrivate.duplicate());
            Logger.info("[AUDIO] JAAD AAC decoder initialized successfully");

            AACDecoderConfig cfg = jaad.getConfig();
            int profile = cfg != null ? cfg.getProfile().getIndex() : 1;
            int sampleRateIndex = getSampleRateIndex(cfg != null ? cfg.getSampleFrequency().getFrequency() : 44100);
            int channelConfig = cfg != null ? cfg.getChannelConfiguration().getChannelCount() : 2;

            javax.sound.sampled.AudioFormat javaFormat = null;
            int decodedFrames = 0;
            long totalPcmBytes = 0;

            ByteBuffer pcmScratch = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < rawPackets.size(); i++) {
                if (stop != null && stop.get()) {
                    break;
                }

                byte[] rawPacket = rawPackets.get(i);
                byte[] adtsPacket = addAdtsHeader(rawPacket, profile, sampleRateIndex, channelConfig);

                pcmScratch.clear();
                ByteBuffer decodedBuf = null;
                try {
                    decodedBuf = jaad.decodeFrame(ByteBuffer.wrap(adtsPacket), pcmScratch);
                } catch (Throwable ex) {
                    // Log full stack trace for the first packet so we see exactly why it's failing
                    if (i == 0) {
                        Logger.error(ex, "[AUDIO] CRITICAL: Exception decoding packet 0!");
                    } else if (i < 5) {
                        Logger.error("[AUDIO] Exception decoding packet {}: {}", i, ex.getMessage());
                    }
                    continue;
                }

                if (decodedBuf == null) {
                    if (i < 5) {
                        Logger.warn("[AUDIO] Packet {} returned null decoded buffer", i);
                    }
                    continue;
                }

                decodedBuf.flip();
                int pcmSize = decodedBuf.remaining();
                if (pcmSize <= 0) {
                    continue;
                }

                if (javaFormat == null) {
                    int sampleRate = cfg != null ? cfg.getSampleFrequency().getFrequency() : 44100;
                    int channels = cfg != null ? cfg.getChannelConfiguration().getChannelCount() : 2;

                    javaFormat = new javax.sound.sampled.AudioFormat(
                            Encoding.PCM_SIGNED,
                            sampleRate,
                            16,
                            channels,
                            channels * 2,
                            sampleRate,
                            false
                    );

                    Logger.info("[AUDIO] PCM format: {} Hz, {} ch, 16-bit", sampleRate, channels);

                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, javaFormat);
                    if (!AudioSystem.isLineSupported(info)) {
                        Logger.warn("[AUDIO] Java Sound does not support format: {}", javaFormat);
                        return;
                    }

                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(javaFormat, Math.max(javaFormat.getFrameSize() * 4096, 16384));
                    line.start();
                    Logger.info("[AUDIO] Java Sound playback started");
                }

                byte[] pcmBytes = new byte[pcmSize];
                decodedBuf.get(pcmBytes);

                decodedFrames++;
                totalPcmBytes += pcmBytes.length;
                pcmCollector.write(pcmBytes, 0, pcmBytes.length);

                int offset = 0;
                while (offset < pcmBytes.length && (stop == null || !stop.get())) {
                    int written = line.write(pcmBytes, offset, pcmBytes.length - offset);
                    if (written <= 0) {
                        break;
                    }
                    offset += written;
                }
            }

            Logger.info("[AUDIO] Decode loop finished: {} AAC frames, {} PCM bytes", decodedFrames, totalPcmBytes);

            if (line != null && (stop == null || !stop.get())) {
                line.drain();
            }
            Logger.info("[AUDIO] Playback finished");

        } catch (Exception ex) {
            Logger.warn(ex, "[AUDIO] Playback failed");
        } finally {
            if (line != null) {
                try { line.stop(); } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
            }
            if (demuxer != null) {
                try { demuxer.close(); } catch (Exception ignored) {}
            }
            if (channel != null) {
                try { channel.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static byte[] addAdtsHeader(byte[] packet, int profile, int sampleRateIndex, int channelConfig) {
        int packetLength = packet.length + 7;
        byte[] adtsPacket = new byte[packetLength];

        adtsPacket[0] = (byte) 0xFF;
        adtsPacket[1] = (byte) 0xF1;
        adtsPacket[2] = (byte) (((profile - 1) << 6) | ((sampleRateIndex & 0x0F) << 2) | ((channelConfig & 0x04) >> 2));
        adtsPacket[3] = (byte) (((channelConfig & 0x03) << 6) | ((packetLength >> 11) & 0x03));
        adtsPacket[4] = (byte) ((packetLength >> 3) & 0xFF);
        adtsPacket[5] = (byte) (((packetLength & 0x07) << 5) | 0x1F);
        adtsPacket[6] = (byte) 0xFC;

        System.arraycopy(packet, 0, adtsPacket, 7, packet.length);
        return adtsPacket;
    }

    private static int getSampleRateIndex(int sampleRate) {
        switch (sampleRate) {
            case 96000: return 0;
            case 88200: return 1;
            case 64000: return 2;
            case 48000: return 3;
            case 44100: return 4;
            case 32000: return 5;
            case 24000: return 6;
            case 22050: return 7;
            case 16000: return 8;
            case 12000: return 9;
            case 11025: return 10;
            case 8000:  return 11;
            case 7350:  return 12;
            default:    return 4;
        }
    }

    public static AtomicBoolean playMp4AacAsync(byte[] mp4Bytes) {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread thread = new Thread(() -> playMp4Aac(mp4Bytes, stop), "mp4-audio");
        thread.setDaemon(true);
        thread.start();
        return stop;
    }
}