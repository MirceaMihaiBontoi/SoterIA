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
 * 
 * <p>This service provides high-quality multilingual TTS with the following features:
 * <ul>
 *   <li>Asynchronous synthesis and playback using dedicated worker threads</li>
 *   <li>Queue-based processing with backpressure (max 100 utterances)</li>
 *   <li>Dynamic language switching with automatic engine rebuild</li>
 *   <li>Configurable speech rate and volume</li>
 *   <li>Audio enhancements: fade in/out, silence trimming, contextual pauses</li>
 *   <li>Thread-safe operations with native library synchronization</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong> This class is thread-safe. All public methods can be called
 * from any thread. Internal synchronization protects native library calls and state mutations.
 * 
 * <p><strong>Resource Management:</strong> Implements {@link AutoCloseable}. Always call
 * {@link #shutdown()} or use try-with-resources to properly release native resources.
 * 
 * <p><strong>Supported Languages:</strong> English (US/UK), Spanish, French, Italian, Portuguese,
 * Chinese (Mandarin), Japanese, Hindi. See {@link #FEMALE_VOICE_MAP} for speaker IDs.
 * 
 * @see TTS
 * @see TTSModelManager
 * @see TTSAudioPlayer
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
    private volatile TTSErrorCallback errorCallback = null;
    private volatile boolean warmupComplete = false;

    private final LinkedBlockingQueue<QueuedUtterance> synthesisQueue = new LinkedBlockingQueue<>(100);
    private final AtomicInteger pendingSynthesis = new AtomicInteger(0);
    private final AtomicBoolean interruptRequested = new AtomicBoolean(false);
    private Thread synthesisThread;
    
    // Prosodic lookahead buffering for natural speech flow
    private final StringBuilder sentenceBuffer = new StringBuilder();
    private final StringBuilder lookaheadBuffer = new StringBuilder();  // Future context for prosody planning
    private final Object bufferLock = new Object();
    private volatile long lastBufferAddTime = 0;
    private static final long BUFFER_FLUSH_TIMEOUT_MS = 100;  // Balance between latency and word completion
    private static final int LOOKAHEAD_WORDS = 8;  // Number of future words to include for prosody planning (increased for better continuity)

    private static final Map<String, Integer> FEMALE_VOICE_MAP = Map.of(
            "en", 0, // af_bella
            "es", 31, // ef_mariela (bundle-specific sid; matches downloaded kokoro-multi-lang-v1_0)
            "ca", 31,
            "fr", 35, // ff_siwis
            "it", 37, // if_sarah
            "pt", 40, // pf_dora
            "zh", 45 // zf_xiaobei — lang zh must not use sid 0; native ONNX has crashed with that combo on Windows
    );

    /**
     * Constructs a TTS service with the specified model path and default English language.
     * 
     * @param modelPath Path to the Kokoro model directory containing model.onnx, voices.bin, etc.
     * @throws IllegalStateException if model initialization fails
     */
    public SherpaTTSService(Path modelPath) {
        this(modelPath, "en");
    }

    /**
     * Constructs a TTS service with the specified model path and language.
     * 
     * <p>The service will:
     * <ol>
     *   <li>Initialize logging system</li>
     *   <li>Load the Kokoro model for the specified language</li>
     *   <li>Resolve the appropriate speaker ID for the language</li>
     *   <li>Start synthesis and playback worker threads</li>
     *   <li>Perform warmup synthesis to reduce first-utterance latency</li>
     * </ol>
     * 
     * @param modelPath Path to the Kokoro model directory
     * @param language Initial language (e.g., "en", "es", "zh")
     * @throws IllegalStateException if model initialization fails
     */
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

        // Start warmup in separate thread to not block synthesis queue processing
        Thread warmupThread = new Thread(() -> {
            try {
                ttsLogger.info("TTS warmup...");
                synchronized (ttsNativeLock) {
                    modelManager.generate(warmupPhrase(language), cachedSpeakerId, speechRate);
                }
                ttsLogger.info("TTS warmup complete");
            } catch (Exception e) {
                ttsLogger.warn("TTS warmup failed: " + e.getMessage());
            } finally {
                warmupComplete = true;
            }
        }, "TTS-Warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();

        // Buffer flush thread - flushes accumulated sentences after timeout
        Thread bufferFlushThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(100);
                    synchronized (bufferLock) {
                        if (lookaheadBuffer.length() > 0) {
                            long timeSinceLastAdd = System.currentTimeMillis() - lastBufferAddTime;
                            if (timeSinceLastAdd >= BUFFER_FLUSH_TIMEOUT_MS) {
                                String bufferedText = lookaheadBuffer.toString().trim();
                                lookaheadBuffer.setLength(0);
                                
                                if (!bufferedText.isEmpty()) {
                                    int wordCount = bufferedText.split("\\s+").length;
                                    pendingSynthesis.incrementAndGet();
                                    if (!synthesisQueue.offer(new QueuedUtterance(bufferedText, this.language, wordCount))) {
                                        ttsLogger.warn("TTS synthesis queue full during buffer flush");
                                        pendingSynthesis.decrementAndGet();
                                    }
                                }
                            }
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "TTS-BufferFlush");
        bufferFlushThread.setDaemon(true);
        bufferFlushThread.start();

        synthesisThread = new Thread(this::processSynthesisQueue, "TTS-Synthesis");
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
                    synthesizeText(item.text(), item.sanitizeLanguageHint(), item.actualWordCount());
                } else if (item != null) {
                    pendingSynthesis.decrementAndGet();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void synthesizeText(String text, String sanitizeLanguageHint, int actualWordCount) {
        try {
            String trimmedText = TtsTextSanitizer.sanitize(text, sanitizeLanguageHint);
            if (trimmedText.isEmpty()) {
                return;
            }
            
            // Extract actual content (without lookahead) for audio truncation
            String[] words = trimmedText.split("\\s+");
            String actualContent = actualWordCount > 0 && actualWordCount < words.length
                    ? String.join(" ", java.util.Arrays.copyOfRange(words, 0, actualWordCount))
                    : trimmedText;
            
            float currentSpeechRate = calculateSpeechRate(actualContent);

            GeneratedAudio audio;
            synchronized (ttsNativeLock) {
                modelManager.ensureEngineLanguage(this.language);
                // Generate with full text (including lookahead) for prosody planning
                audio = modelManager.generate(trimmedText, this.cachedSpeakerId, currentSpeechRate);
            }

            if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                // Truncate audio to match actual content (without lookahead)
                float[] samples = audio.getSamples();
                if (actualWordCount > 0 && actualWordCount < words.length) {
                    // Estimate audio length for actual content
                    float ratio = (float) actualContent.length() / trimmedText.length();
                    int truncateLength = (int) (samples.length * ratio);
                    samples = java.util.Arrays.copyOfRange(samples, 0, Math.min(truncateLength, samples.length));
                }
                
                processAndEnqueueAudio(samples, actualContent, currentSpeechRate);
            } else {
                ttsLogger.warn("TTS: empty audio for: " + trimmedText);
                notifyError(text, new IllegalStateException("Empty audio generated"));
            }
        } catch (Exception ex) {
            ttsLogger.error("TTS synthesis error", ex);
            notifyError(text, ex);
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

    private void processAndEnqueueAudio(float[] samples, String text, float rate) {
        float[] trimmedSamples = trimSilence(samples);
        if (trimmedSamples.length == 0) return;

        byte[] pcm = audioPlayer.floatToPcm16(trimmedSamples);
        audioPlayer.applyFadeIn(pcm);
        audioPlayer.applyFadeOut(pcm);

        ttsLogger.logSynthesis(this.language, text, (trimmedSamples.length * 1000L) / SAMPLE_RATE, rate);

        if (!interruptRequested.get()) {
            audioPlayer.enqueue(pcm);
            audioPlayer.enqueue(audioPlayer.generateSilence(calculateSilenceMs(text)));
        }
    }

    private int calculateSilenceMs(String text) {
        // NO artificial pauses - Kokoro generates all natural pauses internally
        // The delay between chunks comes from LLM token generation, not from TTS
        return 0;
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
        int wordCount = text.trim().split("\\s+").length;
        if (!synthesisQueue.offer(new QueuedUtterance(text, this.language, wordCount))) {
            ttsLogger.warn("TTS synthesis queue full, dropping utterance");
            pendingSynthesis.decrementAndGet();
            notifyError(text, new IllegalStateException("TTS queue full"));
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
        
        long receiveTime = System.currentTimeMillis();
        ttsLogger.info(String.format("[SPEAKQUEUED] Received: \"%s\" at %d", text, receiveTime));
        
        // Prosodic lookahead buffering: accumulate text and use future context for natural prosody
        synchronized (bufferLock) {
            // Add new text to lookahead buffer
            lookaheadBuffer.append(text);
            if (!text.endsWith(" ")) {
                lookaheadBuffer.append(" ");
            }
            lastBufferAddTime = System.currentTimeMillis();
            
            // Check if we have a prosodic boundary (major: .!? or minor: ,)
            boolean hasMajorBoundary = text.matches(".*[.!?。！？]\\s*$");
            boolean hasMinorBoundary = text.matches(".*[,，]\\s*$");
            
            if (hasMajorBoundary || hasMinorBoundary) {
                String accumulated = lookaheadBuffer.toString();
                int lastBoundaryIdx = hasMajorBoundary 
                        ? findLastProsodyBoundary(accumulated)
                        : findLastCommaBoundary(accumulated);
                
                if (lastBoundaryIdx > 0) {
                    // Split at boundary: current chunk + lookahead
                    String currentChunk = accumulated.substring(0, lastBoundaryIdx).trim();
                    String remainingText = accumulated.substring(lastBoundaryIdx).trim();
                    
                    // Extract lookahead words (next 5 words after boundary)
                    String[] remainingWords = remainingText.isEmpty() ? new String[0] : remainingText.split("\\s+");
                    int lookaheadCount = Math.min(LOOKAHEAD_WORDS, remainingWords.length);
                    String lookaheadContext = lookaheadCount > 0 
                            ? String.join(" ", java.util.Arrays.copyOfRange(remainingWords, 0, lookaheadCount))
                            : "";
                    
                    // Synthesize current chunk WITH lookahead context for prosody planning
                    String textWithLookahead = lookaheadContext.isEmpty() 
                            ? currentChunk 
                            : currentChunk + " " + lookaheadContext;
                    
                    long queueTime = System.currentTimeMillis();
                    ttsLogger.info(String.format("[QUEUE] Queuing: \"%s\" at %d (delay from receive: %dms)", 
                            currentChunk, queueTime, queueTime - receiveTime));
                    
                    pendingSynthesis.incrementAndGet();
                    if (!synthesisQueue.offer(new QueuedUtterance(textWithLookahead, hint, currentChunk.split("\\s+").length))) {
                        ttsLogger.warn("TTS synthesis queue full, dropping utterance");
                        pendingSynthesis.decrementAndGet();
                        notifyError(currentChunk, new IllegalStateException("TTS queue full"));
                    }
                    
                    // Keep remaining text in lookahead buffer
                    lookaheadBuffer.setLength(0);
                    if (!remainingText.isEmpty()) {
                        lookaheadBuffer.append(remainingText).append(" ");
                    }
                }
            }
            
            // Flush if buffer is getting too large (safety mechanism)
            if (lookaheadBuffer.length() > 300) {
                String bufferedText = lookaheadBuffer.toString().trim();
                lookaheadBuffer.setLength(0);
                
                if (!bufferedText.isEmpty()) {
                    pendingSynthesis.incrementAndGet();
                    if (!synthesisQueue.offer(new QueuedUtterance(bufferedText, hint, bufferedText.split("\\s+").length))) {
                        ttsLogger.warn("TTS synthesis queue full during buffer overflow");
                        pendingSynthesis.decrementAndGet();
                        notifyError(bufferedText, new IllegalStateException("TTS queue full"));
                    }
                }
            }
        }
    }
    
    /**
     * Finds the last prosodic boundary (sentence end) in the text.
     * Returns the index after the boundary character, or -1 if not found.
     */
    private int findLastProsodyBoundary(String text) {
        int lastIdx = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                lastIdx = i + 1;
                break;
            }
        }
        return lastIdx;
    }
    
    /**
     * Finds the last comma boundary in the text.
     * Returns the index after the comma character, or -1 if not found.
     */
    private int findLastCommaBoundary(String text) {
        int lastIdx = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ',' || c == '，') {
                lastIdx = i + 1;
                break;
            }
        }
        return lastIdx;
    }

    @Override
    public void stop() {
        interruptRequested.set(true);
        synthesisQueue.clear();
        pendingSynthesis.set(0);
        
        // Clear all buffers
        synchronized (bufferLock) {
            sentenceBuffer.setLength(0);
            lookaheadBuffer.setLength(0);
        }
        
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

    /**
     * Sets whether TTS output is muted.
     * 
     * <p>When muted, all synthesis and playback is stopped immediately.
     * New synthesis requests are silently ignored until unmuted.
     * 
     * @param muted true to mute, false to unmute
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted) stop();
    }

    /**
     * Checks if the TTS warmup phase has completed.
     * @return true if warmup is complete, false otherwise
     */
    public boolean isWarmupComplete() {
        return warmupComplete;
    }

    @Override
    public void setErrorCallback(TTSErrorCallback callback) {
        this.errorCallback = callback;
    }

    private void notifyError(String text, Throwable error) {
        TTSErrorCallback callback = this.errorCallback;
        if (callback != null) {
            try {
                callback.onError(text, error);
            } catch (Exception e) {
                ttsLogger.error("Error in TTS error callback", e);
            }
        }
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

    private record QueuedUtterance(String text, String sanitizeLanguageHint, int actualWordCount) {}
}
