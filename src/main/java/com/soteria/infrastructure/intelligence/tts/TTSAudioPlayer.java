package com.soteria.infrastructure.intelligence.tts;

import javax.sound.sampled.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles audio playback and PCM processing for the TTS service.
 * Manages the audio line and a dedicated playback thread.
 * Supports crossfading between audio chunks for smooth transitions.
 */
public class TTSAudioPlayer implements AutoCloseable {
    private static final int SAMPLE_RATE = 24000;
    private static final int FADE_MS = 5;
    private static final int FADE_SAMPLES = (SAMPLE_RATE * FADE_MS) / 1000;
    private static final int PLAYBACK_CHUNK_BYTES = 8192;
    private static final int CROSSFADE_MS = 30;  // 30ms crossfade between chunks
    private static final int CROSSFADE_SAMPLES = (SAMPLE_RATE * CROSSFADE_MS) / 1000;

    private final TTSLogger ttsLogger;
    private final LinkedBlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean interruptRequested = new AtomicBoolean(false);
    
    private SourceDataLine persistentLine;
    private Thread playbackThread;
    private volatile boolean running = false;
    private volatile float volume = 1.0f;
    private byte[] previousChunkTail = null;  // For crossfading

    public TTSAudioPlayer(TTSLogger ttsLogger) {
        this.ttsLogger = ttsLogger;
    }

    public void start() {
        running = true;
        playbackThread = new Thread(this::processPlaybackQueue, "TTS-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void enqueue(byte[] pcm) {
        try {
            playbackQueue.put(pcm);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ttsLogger.error("Failed to enqueue PCM for playback", e);
        }
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    public void stop() {
        interruptRequested.set(true);
        playbackQueue.clear();
        previousChunkTail = null;  // Clear crossfade buffer
        if (persistentLine != null && persistentLine.isOpen()) {
            persistentLine.stop();
            persistentLine.flush();
            persistentLine.start();
        }
        interruptRequested.set(false);
    }

    public void clearQueue() {
        playbackQueue.clear();
    }

    private void processPlaybackQueue() {
        if (!openPersistentLine()) return;

        while (running) {
            try {
                byte[] pcm = playbackQueue.poll(100, TimeUnit.MILLISECONDS);
                if (pcm != null && !interruptRequested.get()) {
                    // Apply crossfading if we have a previous chunk
                    if (previousChunkTail != null && pcm.length > 0) {
                        pcm = applyCrossfade(previousChunkTail, pcm);
                    }
                    
                    playPcm(pcm);
                    
                    // Save tail of this chunk for next crossfade
                    int tailBytes = Math.min(CROSSFADE_SAMPLES * 2, pcm.length);
                    if (tailBytes > 0) {
                        previousChunkTail = new byte[tailBytes];
                        System.arraycopy(pcm, pcm.length - tailBytes, previousChunkTail, 0, tailBytes);
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                ttsLogger.warn("TTS playback error: " + ex.getMessage());
            }
        }
        closePersistentLine();
    }

    private boolean openPersistentLine() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            persistentLine = (SourceDataLine) AudioSystem.getLine(info);
            persistentLine.open(format, (int) (format.getFrameSize() * format.getSampleRate() / 10));
            persistentLine.start();
            ttsLogger.info("TTS audio line opened: 24000Hz 16-bit mono");
            return true;
        } catch (Exception e) {
            ttsLogger.error("Failed to open audio line", e);
            return false;
        }
    }

    private void playPcm(byte[] audioData) {
        if ((persistentLine == null || !persistentLine.isOpen()) && !openPersistentLine()) {
            return;
        }

        int frameSize = persistentLine.getFormat().getFrameSize();
        for (int offset = 0; offset < audioData.length && !interruptRequested.get(); offset += PLAYBACK_CHUNK_BYTES) {
            int remaining = audioData.length - offset;
            int thisChunk = (Math.min(PLAYBACK_CHUNK_BYTES, remaining) / frameSize) * frameSize;
            if (thisChunk > 0) {
                persistentLine.write(audioData, offset, thisChunk);
            }
        }

        if (!interruptRequested.get()) {
            persistentLine.drain();
        } else {
            persistentLine.flush();
        }
    }

    private void closePersistentLine() {
        if (persistentLine != null && persistentLine.isOpen()) {
            persistentLine.stop();
            persistentLine.close();
            ttsLogger.info("TTS audio line closed");
        }
    }

    // --- Audio Processing Utilities ---

    public byte[] floatToPcm16(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i] * volume;
            if (s > 1.0f) s = 1.0f;
            if (s < -1.0f) s = -1.0f;
            short val = (short) (s * 32767.0f);
            pcm[i * 2] = (byte) (val & 0xFF);
            pcm[i * 2 + 1] = (byte) ((val >>> 8) & 0xFF);
        }
        return pcm;
    }

    public void applyFadeIn(byte[] pcm) {
        int samplesToFade = Math.min(FADE_SAMPLES, pcm.length / 2);
        for (int i = 0; i < samplesToFade && (i * 2 + 1) < pcm.length; i++) {
            int lo = pcm[2 * i] & 0xFF;
            int hi = pcm[2 * i + 1] & 0xFF;
            short sample = (short) ((hi << 8) | lo);
            float gain = (float) i / samplesToFade;
            int scaled = Math.round(sample * gain);
            pcm[2 * i] = (byte) (scaled & 0xFF);
            pcm[2 * i + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }

    public void applyFadeOut(byte[] pcm) {
        int totalSamples = pcm.length / 2;
        int samplesToFade = Math.min(FADE_SAMPLES, totalSamples);
        int startSample = totalSamples - samplesToFade;
        for (int i = startSample; i < totalSamples && (i * 2 + 1) < pcm.length; i++) {
            int lo = pcm[2 * i] & 0xFF;
            int hi = pcm[2 * i + 1] & 0xFF;
            short sample = (short) ((hi << 8) | lo);
            float gain = (float) (totalSamples - i) / samplesToFade;
            int scaled = Math.round(sample * gain);
            pcm[2 * i] = (byte) (scaled & 0xFF);
            pcm[2 * i + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }

    public byte[] generateSilence(int ms) {
        return new byte[(int) (SAMPLE_RATE * (ms / 1000f)) * 2];
    }

    public boolean isQueueEmpty() {
        return playbackQueue.isEmpty();
    }

    /**
     * Applies crossfading between two audio chunks for smooth transitions.
     * 
     * @param previousTail Tail of the previous chunk
     * @param currentChunk Current chunk to play
     * @return Crossfaded audio
     */
    private byte[] applyCrossfade(byte[] previousTail, byte[] currentChunk) {
        int crossfadeBytes = Math.min(CROSSFADE_SAMPLES * 2, Math.min(previousTail.length, currentChunk.length));
        if (crossfadeBytes < 4) {
            return currentChunk;  // Not enough data to crossfade
        }
        
        // Create result: previous (without tail) + crossfaded region + current (without head)
        int previousKeep = previousTail.length - crossfadeBytes;
        int currentKeep = currentChunk.length - crossfadeBytes;
        byte[] result = new byte[previousKeep + crossfadeBytes + currentKeep];
        
        // Copy non-crossfaded part of previous
        if (previousKeep > 0) {
            System.arraycopy(previousTail, 0, result, 0, previousKeep);
        }
        
        // Crossfade region
        for (int i = 0; i < crossfadeBytes; i += 2) {
            int prevIdx = previousKeep + i;
            int currIdx = i;
            
            // Read samples
            short prevSample = (short) (((previousTail[prevIdx + 1] & 0xFF) << 8) | (previousTail[prevIdx] & 0xFF));
            short currSample = (short) (((currentChunk[currIdx + 1] & 0xFF) << 8) | (currentChunk[currIdx] & 0xFF));
            
            // Calculate crossfade weights
            float progress = (float) i / crossfadeBytes;
            float prevWeight = 1.0f - progress;
            float currWeight = progress;
            
            // Mix samples
            int mixed = Math.round(prevSample * prevWeight + currSample * currWeight);
            mixed = Math.max(-32768, Math.min(32767, mixed));
            
            // Write mixed sample
            result[previousKeep + i] = (byte) (mixed & 0xFF);
            result[previousKeep + i + 1] = (byte) ((mixed >>> 8) & 0xFF);
        }
        
        // Copy non-crossfaded part of current
        if (currentKeep > 0) {
            System.arraycopy(currentChunk, crossfadeBytes, result, previousKeep + crossfadeBytes, currentKeep);
        }
        
        return result;
    }

    @Override
    public void close() {
        running = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(2000);
            } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        closePersistentLine();
    }
}
