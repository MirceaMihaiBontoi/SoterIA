package com.soteria.infrastructure.intelligence.tts;

import com.soteria.core.port.TTS;
import com.k2fsa.sherpa.onnx.GeneratedAudio;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text-to-Speech service using sherpa-onnx with Kokoro-82M model.
 * Modularized version delegating audio and model management.
 */
public class SherpaTTSService implements TTS, AutoCloseable {
    private static final int SAMPLE_RATE = 24000;

    private final TTSLogger ttsLogger;
    private final TTSModelManager modelManager;
    private final TTSAudioPlayer audioPlayer;
    
    private float speechRate = 1.44f;
    private String language = "en";
    private int cachedSpeakerId = 0;
    private volatile boolean muted = false;
    private volatile boolean running = false;

    private final LinkedBlockingQueue<String> synthesisQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger pendingSynthesis = new AtomicInteger(0);
    private final AtomicBoolean interruptRequested = new AtomicBoolean(false);
    private Thread synthesisThread;

    private static final Map<String, Integer> FEMALE_VOICE_MAP = Map.of(
            "en", 0, // af_bella
            "es", 31, // ef_mariela
            "fr", 35, // ff_siwis
            "it", 37, // if_sarah
            "pt", 40 // pf_dora
    );

    public SherpaTTSService(Path modelPath) {
        this(modelPath, "en");
    }

    public SherpaTTSService(Path modelPath, String language) {
        this.ttsLogger = new TTSLogger();
        this.ttsLogger.setup();
        
        this.modelManager = new TTSModelManager(modelPath, ttsLogger, language);
        this.audioPlayer = new TTSAudioPlayer(ttsLogger);
        this.language = language;
        this.cachedSpeakerId = resolveSpeakerId(language);
        
        startWorkerThreads();
    }

    private void startWorkerThreads() {
        running = true;
        audioPlayer.start();

        synthesisThread = new Thread(() -> {
            try {
                ttsLogger.info("TTS warmup...");
                modelManager.generate("Listo.", cachedSpeakerId, speechRate);
                ttsLogger.info("TTS warmup complete");
            } catch (Exception e) {
                ttsLogger.warn("TTS warmup failed: " + e.getMessage());
            }
            processSynthesisQueue();
        }, "TTS-Synthesis");
        synthesisThread.setDaemon(true);
        synthesisThread.start();
    }

    private void processSynthesisQueue() {
        while (running) {
            try {
                String text = synthesisQueue.poll(100, TimeUnit.MILLISECONDS);
                if (text != null && !interruptRequested.get()) {
                    synthesizeText(text);
                } else if (text != null) {
                    pendingSynthesis.decrementAndGet();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void synthesizeText(String text) {
        try {
            String trimmedText = text.trim();
            float currentSpeechRate = calculateSpeechRate(trimmedText);

            modelManager.ensureEngineLanguage(this.language);
            GeneratedAudio audio = modelManager.generate(text, this.cachedSpeakerId, currentSpeechRate);

            if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                processAndEnqueueAudio(audio, trimmedText, currentSpeechRate);
            } else {
                ttsLogger.warn("TTS: empty audio for: " + text);
            }
        } catch (Exception ex) {
            ttsLogger.error("TTS synthesis error", ex);
        } finally {
            pendingSynthesis.decrementAndGet();
        }
    }

    private float calculateSpeechRate(String trimmedText) {
        if (trimmedText.endsWith("?")) {
            return this.speechRate * 0.90f;
        }
        return this.speechRate;
    }

    private void processAndEnqueueAudio(GeneratedAudio audio, String text, float rate) {
        float[] samples = trimSilence(audio.getSamples());
        if (samples.length == 0) return;

        byte[] pcm = audioPlayer.floatToPcm16(samples);
        audioPlayer.applyFadeIn(pcm);
        audioPlayer.applyFadeOut(pcm);

        ttsLogger.logSynthesis(this.language, text, (samples.length * 1000L) / SAMPLE_RATE, rate);

        if (!interruptRequested.get()) {
            audioPlayer.enqueue(pcm);
            audioPlayer.enqueue(audioPlayer.generateSilence(calculateSilenceMs(text)));
        }
    }

    private int calculateSilenceMs(String text) {
        if (text.endsWith(".")) return 150;
        if (text.endsWith("?")) return 120;
        if (text.endsWith(",")) return 60;
        return 30;
    }

    private float[] trimSilence(float[] samples) {
        int start = 0;
        while (start < samples.length && Math.abs(samples[start]) < 0.012f) start++;
        int end = samples.length;
        while (end > start && Math.abs(samples[end - 1]) < 0.012f) end--;
        
        if (start >= end) return new float[0];
        if (start == 0 && end == samples.length) return samples;

        float[] result = new float[end - start];
        System.arraycopy(samples, start, result, 0, result.length);
        return result;
    }

    private int resolveSpeakerId(String lang) {
        String baseLang = modelManager.resolveLanguageCode(lang);
        int speakerId = FEMALE_VOICE_MAP.getOrDefault(baseLang, 0);
        ttsLogger.info("Resolved speaker ID " + speakerId + " for language: " + lang);
        return speakerId;
    }

    @Override
    public void speak(String text) {
        if (muted || text == null || text.trim().isEmpty()) return;
        stop();
        pendingSynthesis.set(1);
        if (!synthesisQueue.offer(text)) {
            ttsLogger.warn("TTS synthesis queue full");
            pendingSynthesis.decrementAndGet();
        }
    }

    @Override
    public void speakQueued(String text) {
        if (muted || text == null || text.trim().isEmpty()) return;
        pendingSynthesis.incrementAndGet();
        if (!synthesisQueue.offer(text)) {
            ttsLogger.warn("TTS synthesis queue full");
            pendingSynthesis.decrementAndGet();
        }
    }

    @Override
    public void stop() {
        interruptRequested.set(true);
        synthesisQueue.clear();
        pendingSynthesis.set(0);
        audioPlayer.stop();
        interruptRequested.set(false);
    }

    @Override
    public void setSpeechRate(float rate) {
        this.speechRate = Math.clamp(rate, 0.5f, 2.0f);
    }

    @Override
    public void setVolume(float volume) {
        audioPlayer.setVolume(Math.clamp(volume, 0.0f, 1.0f));
    }

    @Override
    public boolean isSpeaking() {
        return pendingSynthesis.get() > 0 || !synthesisQueue.isEmpty() || !audioPlayer.isQueueEmpty();
    }

    @Override
    public void setLanguage(String language) {
        this.language = language;
        this.cachedSpeakerId = resolveSpeakerId(language);
        modelManager.ensureEngineLanguage(language);
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted) stop();
    }

    @Override
    public void shutdown() {
        stop();
        running = false;
        if (synthesisThread != null) {
            synthesisThread.interrupt();
            try {
                synthesisThread.join(2000);
            } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        audioPlayer.close();
        modelManager.close();
        ttsLogger.info("TTS Service shut down");
    }

    @Override
    public void close() {
        shutdown();
    }
}
