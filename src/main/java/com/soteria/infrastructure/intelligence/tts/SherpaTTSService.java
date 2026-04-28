package com.soteria.infrastructure.intelligence.tts;

import com.soteria.core.port.TTS;
import com.k2fsa.sherpa.onnx.*;
import javax.sound.sampled.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Text-to-Speech service using sherpa-onnx with Kokoro-82M model.
 * Provides high-quality offline speech synthesis for emergency responses.
 *
 * Architecture:
 * - Synthesis thread: takes text from synthesisQueue, runs tts.generate()
 * (blocking),
 * applies fade-in/fade-out de-clicking, and puts the finished PCM into
 * playbackQueue.
 * - Playback thread: reads PCM from playbackQueue, writes to a persistent
 * SourceDataLine
 * in 8KB chunks with a short inter-sentence silence for natural transitions.
 * - This separation ensures synthesis of sentence N+1 begins immediately after
 * N finishes,
 * while N's audio is still playing from the playback queue.
 */
public class SherpaTTSService implements TTS, AutoCloseable {
    private static final Logger logger = Logger.getLogger(SherpaTTSService.class.getName());
    private static final int SAMPLE_RATE = 24000;
    private static final int FADE_MS = 5;
    private static final int FADE_SAMPLES = (SAMPLE_RATE * FADE_MS) / 1000; // 120 samples = 5ms
    private static final int PLAYBACK_CHUNK_BYTES = 8192;

    private final Path modelPath;
    private float speechRate = 1.44f;
    private float volume = 1.0f;
    private String language = "en";
    private int cachedSpeakerId = 0;
    private volatile boolean muted = false;

    private OfflineTts offlineTts;
    private SourceDataLine persistentLine;
    private volatile boolean running = false;

    // Synthesis pipeline: text → synthesisQueue → synthesis thread → playbackQueue
    // → playback thread → audio
    private final LinkedBlockingQueue<String> synthesisQueue = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger pendingSynthesis = new AtomicInteger(0);
    private final AtomicBoolean interruptRequested = new AtomicBoolean(false);
    private Thread synthesisThread;
    private Thread playbackThread;

    // Default female voices for different languages in Kokoro v1.0
    private static final Map<String, Integer> FEMALE_VOICE_MAP = Map.of(
            "en", 0, // af_bella
            "es", 31, // ef_mariela (Spanish female)
            "fr", 35, // ff_siwis (French female)
            "it", 37, // if_sarah (Italian female)
            "pt", 40 // pf_dora (Portuguese female)
    );

    private static final Map<String, String> LANG_CODE_MAP = Map.ofEntries(
            Map.entry("spanish", "es"),
            Map.entry("español", "es"),
            Map.entry("castellano", "es"),
            Map.entry("english", "en"),
            Map.entry("inglés", "en"),
            Map.entry("french", "fr"),
            Map.entry("français", "fr"),
            Map.entry("francés", "fr"),
            Map.entry("italian", "it"),
            Map.entry("italiano", "it"),
            Map.entry("portuguese", "pt"),
            Map.entry("português", "pt"),
            Map.entry("portugués", "pt"));

    public SherpaTTSService(Path modelPath) {
        this.modelPath = modelPath;
        this.cachedSpeakerId = resolveSpeakerId(this.language);
        initializeSherpaOnnx();
        startWorkerThreads();
    }

    public SherpaTTSService(Path modelPath, String language) {
        this.modelPath = modelPath;
        this.language = language;
        this.cachedSpeakerId = resolveSpeakerId(language);
        initializeSherpaOnnx();
        startWorkerThreads();
    }

    private void loadNativeLibraries() {
        try {
            String userDir = System.getProperty("user.dir");
            Path nativeDir = Paths.get(userDir, "lib", "native");
            System.load(nativeDir.resolve("onnxruntime.dll").toAbsolutePath().toString());
            System.load(nativeDir.resolve("sherpa-onnx-jni.dll").toAbsolutePath().toString());
            logger.info("Native sherpa-onnx libraries loaded successfully from lib/native");
        } catch (UnsatisfiedLinkError | Exception e) {
            logger.log(Level.WARNING, "Failed to load native libraries manually: {0}", e.getMessage());
        }
    }

    private void initializeSherpaOnnx() {
        try {
            loadNativeLibraries();

            logger.log(Level.INFO, "Initializing sherpa-onnx TTS with model: {0}", modelPath);

            String lexiconPath = modelPath.resolve("lexicon-zh.txt") + "," + modelPath.resolve("lexicon-us-en.txt");
            String fstPath = modelPath.resolve("phone-zh.fst") + "," + modelPath.resolve("date-zh.fst") + ","
                    + modelPath.resolve("number-zh.fst");

            OfflineTtsKokoroModelConfig kokoroConfig = OfflineTtsKokoroModelConfig.builder()
                    .setModel(modelPath.resolve("model.onnx").toString())
                    .setVoices(modelPath.resolve("voices.bin").toString())
                    .setTokens(modelPath.resolve("tokens.txt").toString())
                    .setDataDir(modelPath.resolve("espeak-ng-data").toString())
                    .setDictDir(modelPath.resolve("dict").toString())
                    .setLexicon(lexiconPath)
                    .setLang(resolveLanguageCode(this.language))
                    .build();

            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                    .setKokoro(kokoroConfig)
                    .setNumThreads(4)
                    .setDebug(false)
                    .build();

            OfflineTtsConfig config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setRuleFsts(fstPath)
                    .setMaxNumSentences(1)
                    .build();

            offlineTts = new OfflineTts(config);

            logger.info("TTS Service initialized successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize sherpa-onnx TTS", e);
            throw new IllegalStateException("TTS initialization failed", e);
        }
    }

    private boolean openPersistentLine() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            persistentLine = (SourceDataLine) AudioSystem.getLine(info);
            // Buffer = sampleRate/10 = 100ms — small enough for low latency
            persistentLine.open(format, (int) (format.getFrameSize() * format.getSampleRate() / 10));
            persistentLine.start();
            logger.info("TTS audio line opened: 24000Hz 16-bit mono");
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to open audio line", e);
            return false;
        }
    }

    private void closePersistentLine() {
        if (persistentLine != null && persistentLine.isOpen()) {
            persistentLine.stop();
            persistentLine.close();
        }
    }

    private void startWorkerThreads() {
        running = true;

        // Warmup on the synthesis thread — first generate() primes ONNX graph
        synthesisThread = new Thread(() -> {
            // Warmup: single short synthesis to prime the ONNX execution graph
            try {
                logger.info("TTS warmup...");
                offlineTts.generate("Listo.", cachedSpeakerId, speechRate);
                logger.info("TTS warmup complete");
            } catch (Exception e) {
                logger.log(Level.WARNING, "TTS warmup failed: {0}", e.getMessage());
            }
            processSynthesisQueue();
        }, "TTS-Synthesis");
        synthesisThread.setDaemon(true);
        synthesisThread.start();

        playbackThread = new Thread(this::processPlaybackQueue, "TTS-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    /**
     * Synthesis loop: takes text from synthesisQueue, synthesizes it (blocking),
     * applies de-clicking fade and volume, then enqueues the PCM for playback.
     */
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
            int sid = this.cachedSpeakerId;
            String trimmedText = text.trim();
            
            // Adjust speech rate slightly slower for questions
            float currentSpeechRate = this.speechRate;
            boolean isQuestion = trimmedText.endsWith("?");
            boolean isStatement = trimmedText.endsWith(".");
            
            if (isQuestion) {
                currentSpeechRate = this.speechRate * 0.90f; // 10% slower for questions
            }

            GeneratedAudio audio = offlineTts.generate(text, sid, currentSpeechRate);

            if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                float[] samples = trimSilence(audio.getSamples());
                if (samples.length > 0) {
                    byte[] pcm = floatToPcm16(samples);
                    applyFadeIn(pcm);
                    applyFadeOut(pcm);

                    logger.log(Level.INFO, "TTS synthesized ({0}): [{1}] → {2}ms audio (rate: {3})",
                            new Object[] { this.language, text, (samples.length * 1000L) / SAMPLE_RATE, currentSpeechRate });

                    if (!interruptRequested.get()) {
                        playbackQueue.put(pcm);
                        
                        // Add dynamic silence after the sentence based on punctuation
                        int silenceMs = 30; // base natural silence
                        if (isStatement) {
                            silenceMs = 150; // slightly longer pause after a period
                        } else if (isQuestion) {
                            silenceMs = 120; // medium pause after a question
                        } else if (trimmedText.endsWith(",")) {
                            silenceMs = 60; // short pause after a comma
                        }
                        
                        // 16-bit mono = 2 bytes per sample
                        byte[] silence = new byte[(int) (SAMPLE_RATE * (silenceMs / 1000f)) * 2];
                        playbackQueue.put(silence);
                    }
                }
            } else {
                logger.log(Level.WARNING, "TTS: empty audio for: {0}", text);
            }
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "TTS synthesis error", ex);
        } finally {
            pendingSynthesis.decrementAndGet();
        }
    }

    /**
     * Playback loop: reads PCM from playbackQueue, writes to the persistent audio
     * line
     * in 8KB chunks. Adds a short inter-sentence silence for natural transitions.
     */
    private void processPlaybackQueue() {
        if (!openPersistentLine())
            return;

        while (running) {
            try {
                byte[] pcm = playbackQueue.poll(100, TimeUnit.MILLISECONDS);
                if (pcm != null && !interruptRequested.get()) {
                    playPcm(pcm);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                logger.log(Level.WARNING, "TTS playback error: {0}", ex.getMessage());
            }
        }
        closePersistentLine();
    }

    /**
     * Writes PCM data to the audio line in small chunks.
     */
    private void playPcm(byte[] audioData) {
        if (persistentLine == null || !persistentLine.isOpen()) {
            if (!openPersistentLine()) return;
        }

        int frameSize = persistentLine.getFormat().getFrameSize();

        // Write in chunks for interruptibility
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

    // --- Audio Processing ---

    private float[] trimSilence(float[] samples) {
        // Trim leading silence
        int start = 0;
        while (start < samples.length && Math.abs(samples[start]) < 0.012f) {
            start++;
        }
        // Trim trailing silence
        int end = samples.length;
        while (end > start && Math.abs(samples[end - 1]) < 0.012f) {
            end--;
        }
        if (start >= end)
            return new float[0];
        if (start == 0 && end == samples.length)
            return samples;

        float[] result = new float[end - start];
        System.arraycopy(samples, start, result, 0, result.length);
        return result;
    }

    private byte[] floatToPcm16(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i] * volume;
            if (s > 1.0f)
                s = 1.0f;
            if (s < -1.0f)
                s = -1.0f;
            short val = (short) (s * 32767.0f);
            pcm[i * 2] = (byte) (val & 0xFF);
            pcm[i * 2 + 1] = (byte) ((val >>> 8) & 0xFF);
        }
        return pcm;
    }

    /**
     * Applies a fade-in envelope to the first FADE_MS of the PCM data.
     * Prevents clicks when audio starts after silence or another sentence.
     */
    private void applyFadeIn(byte[] pcm) {
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

    /**
     * Applies a fade-out envelope to the last FADE_MS of the PCM data.
     * Prevents clicks when audio ends before the next sentence starts.
     */
    private void applyFadeOut(byte[] pcm) {
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

    // --- Language Resolution ---

    private int resolveSpeakerId(String lang) {
        String baseLang = resolveLanguageCode(lang);
        int speakerId = FEMALE_VOICE_MAP.getOrDefault(baseLang, 0);
        logger.log(Level.INFO, "Resolved TTS voice speaker ID {0} for language: {1} (mapped to: {2})",
                new Object[] { speakerId, lang, baseLang });
        return speakerId;
    }

    private String resolveLanguageCode(String lang) {
        if (lang == null || lang.isBlank())
            return "en";
        String lower = lang.toLowerCase().trim();

        if (LANG_CODE_MAP.containsKey(lower)) {
            return LANG_CODE_MAP.get(lower);
        }

        if (lower.length() == 2)
            return lower;

        for (Locale locale : Locale.getAvailableLocales()) {
            if (lower.equalsIgnoreCase(locale.getDisplayLanguage(Locale.ENGLISH)) ||
                    lower.equalsIgnoreCase(locale.getDisplayLanguage(locale)) ||
                    lower.equalsIgnoreCase(locale.getDisplayLanguage(Locale.forLanguageTag("es")))) {
                return locale.getLanguage();
            }
        }

        return "en";
    }

    // --- TTS Interface ---

    @Override
    public void speak(String text) {
        if (muted || text == null || text.trim().isEmpty())
            return;

        // speak() interrupts everything and plays just this text
        stop();
        pendingSynthesis.set(1);
        if (!synthesisQueue.offer(text)) {
            logger.warning("TTS synthesis queue full, dropping speak request");
        }
    }

    /**
     * Queues speech without clearing previous audio. Used for streaming TTS
     * where sentences are fed progressively by the LLM inference engine.
     * 
     * The synthesis thread processes one sentence at a time. While it synthesizes
     * sentence N+1, the playback thread is still playing sentence N from the
     * playbackQueue, ensuring smooth gapless transitions.
     */
    @Override
    public void speakQueued(String text) {
        if (muted || text == null || text.trim().isEmpty())
            return;

        pendingSynthesis.incrementAndGet();
        if (!synthesisQueue.offer(text)) {
            logger.warning("TTS synthesis queue full, dropping speakQueued request");
            pendingSynthesis.decrementAndGet();
        }
    }

    @Override
    public void stop() {
        interruptRequested.set(true);
        synthesisQueue.clear();
        playbackQueue.clear();
        pendingSynthesis.set(0);
        if (persistentLine != null && persistentLine.isOpen()) {
            persistentLine.stop();
            persistentLine.flush();
            persistentLine.start();
        }
        interruptRequested.set(false);
    }

    @Override
    public void setSpeechRate(float rate) {
        this.speechRate = Math.clamp(rate, 0.5f, 2.0f);
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.clamp(volume, 0.0f, 1.0f);
    }

    @Override
    public boolean isSpeaking() {
        return pendingSynthesis.get() > 0
                || !synthesisQueue.isEmpty()
                || !playbackQueue.isEmpty();
    }

    @Override
    public void setLanguage(String language) {
        this.language = language;
        this.cachedSpeakerId = resolveSpeakerId(language);
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted)
            stop();
    }

    @Override
    public void shutdown() {
        stop();
        running = false;
        if (synthesisThread != null) {
            synthesisThread.interrupt();
            try {
                synthesisThread.join(3000);
            } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(2000);
            } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        closePersistentLine();
        if (offlineTts != null) {
            offlineTts.release();
        }
        logger.info("TTS Service shut down");
    }

    @Override
    public void close() {
        shutdown();
    }
}
