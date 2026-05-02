package com.soteria.infrastructure.intelligence.tts;

import com.soteria.core.port.TTS;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.soteria.infrastructure.intelligence.system.LanguageUtils;

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
    private volatile String language = "en";
    private volatile int cachedSpeakerId = 0;
    /** Serializes Kokoro native rebuild + generate (inference thread was calling setLanguage during worker generate). */
    private final Object ttsNativeLock = new Object();
    private volatile boolean muted = false;
    private volatile boolean running = false;

    private final LinkedBlockingQueue<QueuedUtterance> synthesisQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger pendingSynthesis = new AtomicInteger(0);
    private final AtomicBoolean interruptRequested = new AtomicBoolean(false);
    private Thread synthesisThread;

    private static final Map<String, Integer> FEMALE_VOICE_MAP = Map.of(
            "en", 0, // af_bella
            "es", 31, // ef_mariela (bundle-specific sid; matches downloaded kokoro-multi-lang-v1_0)
            "ca", 31,
            "fr", 35, // ff_siwis
            "it", 37, // if_sarah
            "pt", 40, // pf_dora
            "zh", 45 // zf_xiaobei — lang zh must not use sid 0; native ONNX has crashed with that combo on Windows
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
                synchronized (ttsNativeLock) {
                    modelManager.generate(warmupPhrase(language), cachedSpeakerId, speechRate);
                }
                ttsLogger.info("TTS warmup complete");
            } catch (Exception e) {
                ttsLogger.warn("TTS warmup failed: " + e.getMessage());
            }
            processSynthesisQueue();
        }, "TTS-Synthesis");
        synthesisThread.setDaemon(true);
        synthesisThread.start();
    }

    /** Short phrase matching engine language + speaker; Latin warmup with lang zh/jp was crashing ONNX. */
    private static String warmupPhrase(String uiLanguage) {
        String code = LanguageUtils.isoCode(uiLanguage);
        if (code.isEmpty()) {
            code = "en";
        }
        return switch (code) {
            case "zh" -> "你好";
            case "ja" -> "こんにちは";
            case "es" -> "Hola.";
            case "fr" -> "Bonjour.";
            case "de" -> "Hallo.";
            case "it" -> "Ciao.";
            case "pt" -> "Olá.";
            case "ro" -> "Bună.";
            case "ru" -> "Здравствуйте.";
            case "ar" -> "مرحبا.";
            default -> "Hello.";
        };
    }

    private void processSynthesisQueue() {
        while (running) {
            try {
                QueuedUtterance item = synthesisQueue.poll(100, TimeUnit.MILLISECONDS);
                if (item != null && !interruptRequested.get()) {
                    synthesizeText(item.text(), item.sanitizeLanguageHint());
                } else if (item != null) {
                    pendingSynthesis.decrementAndGet();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void synthesizeText(String text, String sanitizeLanguageHint) {
        try {
            String trimmedText = TtsTextSanitizer.sanitize(text, sanitizeLanguageHint);
            if (trimmedText.isEmpty()) {
                return;
            }
            float currentSpeechRate = calculateSpeechRate(trimmedText);

            GeneratedAudio audio;
            synchronized (ttsNativeLock) {
                modelManager.ensureEngineLanguage(this.language);
                audio = modelManager.generate(trimmedText, this.cachedSpeakerId, currentSpeechRate);
            }

            if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                processAndEnqueueAudio(audio, trimmedText, currentSpeechRate);
            } else {
                ttsLogger.warn("TTS: empty audio for: " + trimmedText);
            }
        } catch (Exception ex) {
            ttsLogger.error("TTS synthesis error", ex);
        } finally {
            pendingSynthesis.decrementAndGet();
        }
    }

    private float calculateSpeechRate(String trimmedText) {
        if (trimmedText.endsWith("?") || trimmedText.endsWith("\uFF1F")) {
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
        if (text.endsWith(".") || text.endsWith("\u3002")) return 150;
        if (text.endsWith("?") || text.endsWith("\uFF1F")) return 120;
        if (text.endsWith(",") || text.endsWith("\uFF0C")) return 60;
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
        if (!synthesisQueue.offer(new QueuedUtterance(text, this.language))) {
            ttsLogger.warn("TTS synthesis queue full");
            pendingSynthesis.decrementAndGet();
        }
    }

    @Override
    public void speakQueued(String text) {
        speakQueued(text, this.language);
    }

    @Override
    public void speakQueued(String text, String sanitizeLanguageHint) {
        if (muted || text == null || text.trim().isEmpty()) return;
        String hint = (sanitizeLanguageHint == null || sanitizeLanguageHint.isBlank())
                ? this.language
                : sanitizeLanguageHint;
        pendingSynthesis.incrementAndGet();
        if (!synthesisQueue.offer(new QueuedUtterance(text, hint))) {
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
        synchronized (ttsNativeLock) {
            this.language = language;
            this.cachedSpeakerId = resolveSpeakerId(language);
            modelManager.ensureEngineLanguage(language);
        }
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

    private record QueuedUtterance(String text, String sanitizeLanguageHint) {}
}
