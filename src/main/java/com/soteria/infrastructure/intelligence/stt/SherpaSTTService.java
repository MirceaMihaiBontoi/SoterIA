package com.soteria.infrastructure.intelligence.stt;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.Vad;
import com.soteria.infrastructure.intelligence.system.AudioNormalizer;
import com.soteria.infrastructure.intelligence.system.AudioPreProcessor;
import com.soteria.infrastructure.intelligence.system.AudioUtils;
import com.soteria.infrastructure.intelligence.system.ContextualVAD;
import com.soteria.infrastructure.intelligence.system.LanguageUtils;
import com.soteria.infrastructure.intelligence.system.NativeLibraryLoader;
import com.soteria.core.port.STT;
import com.soteria.core.port.STTListener;
import com.soteria.infrastructure.intelligence.system.ModelManager;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Speech-to-text backed by sherpa-onnx: offline Whisper for decoding and Silero VAD for segment boundaries.
 *
 * <p>Two worker threads cooperate: one captures microphone frames into a bounded queue; the other feeds VAD,
 * emits {@linkplain STTListener#onPartialResult(String) partial} previews during speech, and
 * {@linkplain STTListener#onResult(String) final} results when the VAD closes a segment. Partials throttle
 * full re-decodes of accumulated audio (not per-frame), which keeps CPU bounded while still giving Whisper enough
 * context.</p>
 *
 * <p>Each listening session has an {@linkplain #sttEpoch epoch}: {@link #stopListening()}, restarts, and
 * {@link #close()} bump it so asynchronous transcription work can drop stale output safely.</p>
 * 
 * <p><strong>Professional enhancements:</strong> Uses pre-emphasis filtering, noise gating, dynamic compression,
 * contextual VAD, and greedy search for transcriptions to match commercial STT quality. Note: sherpa-onnx only
 * supports greedy_search for Whisper models.</p>
 */
public class SherpaSTTService implements AutoCloseable, STT {
    private static final Logger logger = Logger.getLogger(SherpaSTTService.class.getName());

    private final OfflineRecognizer greedyRecognizer;  // Recognizer for both partials and finals
    private final OfflineRecognizer beamRecognizer;    // Same as greedy (beam_search not supported)
    private final ExecutorService workerPool;
    private final ModelManager modelManager;
    private final String language;
    private final SherpaSTTVoiceLogWriter voiceLog = new SherpaSTTVoiceLogWriter();

    private volatile boolean listening = false;

    /**
     * Monotonic session identifier incremented when listening stops, restarts, or the service closes.
     * Compared inside transcription to ignore results from an outdated session.
     */
    private final AtomicLong sttEpoch = new AtomicLong(0);
    private final BlockingQueue<float[]> audioQueue = new LinkedBlockingQueue<>(100);
    private final AudioNormalizer normalizer = new AudioNormalizer();
    private final AudioPreProcessor preProcessor = new AudioPreProcessor();

    /**
     * @param modelPath   directory containing Whisper ONNX assets (encoder, decoder, tokens)
     * @param modelManager shared model paths and VAD/STT settings
     * @throws IOException if Whisper or VAD assets are missing or invalid
     */
    public SherpaSTTService(Path modelPath, ModelManager modelManager) throws IOException {
        this(modelPath, "", modelManager);
    }

    /**
     * @param modelPath   directory containing Whisper ONNX assets
     * @param language    BCP 47 / ISO language hint; normalized via {@link LanguageUtils#isoCode(String)}
     * @param modelManager shared model paths and VAD/STT settings
     * @throws IOException if Whisper or VAD assets are missing or invalid
     */
    public SherpaSTTService(Path modelPath, String language, ModelManager modelManager) throws IOException {
        this.modelManager = modelManager;
        this.language = LanguageUtils.isoCode(language);

        NativeLibraryLoader.load();

        // Create single recognizer: sherpa-onnx only supports greedy_search for Whisper
        OfflineRecognizer greedy = SherpaOnnxConfigurator.createWhisperRecognizer(modelPath, this.language, false);
        OfflineRecognizer beam = null;
        try {
            // Reuse greedy recognizer - beam_search not supported by sherpa-onnx for Whisper
            beam = greedy;
            
            // Validate VAD
            Vad probeVad = SherpaOnnxConfigurator.createSileroVad(modelManager);
            try {
                logger.fine("STT: Silero VAD model validated.");
            } finally {
                probeVad.release();
            }
        } catch (IOException e) {
            greedy.release();
            // Don't release beam since it's the same reference as greedy
            throw e;
        }
        
        this.greedyRecognizer = greedy;
        this.beamRecognizer = beam;

        this.workerPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("stt-worker-" + t.hashCode());
            return t;
        });

        voiceLog.setup();
        logger.info("STT: Initialized with professional audio processing pipeline");
    }

    /**
     * Starts capture and processing for this listener. Safe to call again: an active session is torn down first
     * (brief wait) so workers do not overlap on the same queue.
     *
     * @param listener receives partials, finals, and errors; ignored if {@code null}
     */
    @Override
    public void startListening(STTListener listener) {
        if (listener == null) {
            logger.warning("STT: startListening called with null listener");
            return;
        }
        boolean hadActiveCapture;
        synchronized (this) {
            hadActiveCapture = listening;
            if (listening) {
                listening = false;
                sttEpoch.incrementAndGet();
            }
        }
        if (hadActiveCapture) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("STT: interrupted while draining previous capture.");
                return;
            }
            synchronized (this) {
                if (listening) {
                    logger.warning("STT: capture restarted during drain wait; cancelling this startListening.");
                    return;
                }
            }
        }

        long epoch;
        synchronized (this) {
            if (listening) {
                logger.severe("STT: refusing startListening — capture still marked active.");
                return;
            }
            epoch = sttEpoch.incrementAndGet();
            listening = true;
            audioQueue.clear();
        }

        workerPool.submit(() -> runAudioCapture(listener));
        workerPool.submit(() -> runProcessingLoop(listener, epoch));
    }

    /**
     * Reads fixed-size frames from the mic, normalizes, applies professional preprocessing, and enqueues PCM floats for the VAD/processing thread.
     */
    private void runAudioCapture(STTListener listener) {
        AudioFormat format = new AudioFormat(ModelManager.STT_SAMPLE_RATE,
                ModelManager.STT_BIT_DEPTH, ModelManager.STT_CHANNELS, true, false);

        try (TargetDataLine line = AudioUtils.getResilientMic(format)) {
            line.start();
            logger.info("STT: Audio capture started with professional preprocessing pipeline.");

            byte[] byteBuffer = new byte[ModelManager.VAD_WINDOW_SIZE * 2];

            while (listening && !Thread.currentThread().isInterrupted()) {
                int read = line.read(byteBuffer, 0, byteBuffer.length);
                if (read < byteBuffer.length) continue;

                // Step 1: AGC normalization (existing)
                normalizer.normalize(byteBuffer, read);

                // Step 2: Convert to float for professional processing
                float[] samples = new float[ModelManager.VAD_WINDOW_SIZE];
                for (int i = 0; i < samples.length; i++) {
                    short s = (short) ((byteBuffer[i * 2] & 0xFF) | (byteBuffer[i * 2 + 1] << 8));
                    samples[i] = s / 32768.0f;
                }

                // Step 3: Professional audio preprocessing pipeline
                // (noise gate → compression → high-pass → pre-emphasis)
                preProcessor.processFrame(samples);

                if (!audioQueue.offer(samples)) {
                    logger.warning("STT: Audio queue overflow, dropping frame.");
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "STT: Fatal capture error", e);
            synchronized (this) {
                listening = false;
                sttEpoch.incrementAndGet();
            }
            listener.onError(e);
        } finally {
            logger.info("STT: Audio capture stopped.");
        }
    }

    /**
     * Dequeues audio, runs contextual VAD, schedules throttled partial decodes while speech is present, and final decodes
     * on each completed VAD segment.
     *
     * @param listener callback for results
     * @param epoch    session id captured at {@link #startListening(STTListener)}; must match {@link #sttEpoch}
     *                 for transcription to be delivered
     */
    private void runProcessingLoop(STTListener listener, long epoch) {
        final Vad vad;
        try {
            vad = SherpaOnnxConfigurator.createSileroVad(modelManager);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "STT: failed to instantiate session VAD", e);
            synchronized (this) {
                listening = false;
                sttEpoch.incrementAndGet();
            }
            listener.onError(e);
            return;
        }

        try {
            long lastPartialTime = 0;
            List<float[]> activeSpeechBuffer = new ArrayList<>();
            ContextualVAD contextualVAD = new ContextualVAD();

            try {
                while (listening && !Thread.currentThread().isInterrupted()) {
                    float[] samples = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (samples == null) continue;

                    vad.acceptWaveform(samples);
                    
                    // Use contextual VAD: requires 2 out of 3 recent frames to be speech
                    boolean rawSpeech = vad.isSpeechDetected();
                    boolean contextualSpeech = contextualVAD.addDecision(rawSpeech);
                    
                    // Additional check: does this frame have voice-like energy?
                    boolean hasVoiceEnergy = preProcessor.hasVoiceEnergy(samples);

                    if (contextualSpeech && hasVoiceEnergy) {
                        lastPartialTime = handleActiveSpeech(samples, activeSpeechBuffer, lastPartialTime, listener,
                                epoch);
                    }

                    processCompletedSegments(vad, activeSpeechBuffer, listener, epoch);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "STT: Processing loop error", e);
                listener.onError(e);
            }
        } finally {
            vad.release();
        }
    }

    /**
     * Appends the current frame to {@code buffer}. At most roughly once per ~1.2s, if accumulated samples exceed a
     * small minimum, runs a partial decode on the full buffer so the model sees enough context without decoding
     * every frame. The buffer is cleared only when {@link #processCompletedSegments} consumes a finished VAD segment.
     *
     * @param samples         mono PCM frame (same length as VAD window)
     * @param buffer          chunks for the current VAD speech span
     * @param lastPartialTime last wall-clock time (ms) a partial was emitted, or {@code 0}
     * @param listener        partial callback target
     * @param epoch           current session; must match {@link #sttEpoch} inside transcribe
     * @return updated {@code lastPartialTime} if a partial was sent, otherwise the previous value
     */
    private long handleActiveSpeech(float[] samples, List<float[]> buffer, long lastPartialTime,
            STTListener listener, long epoch) {
        buffer.add(samples);
        long now = System.currentTimeMillis();
        if (now - lastPartialTime > 1200) {
            float[] fullActive = flatten(buffer);
            // Increased threshold from 4800 to 9600 samples (~0.6s @ 16kHz) to ensure Whisper has enough
            // context and avoid spurious tokens like "[" or "]" in partial results
            if (fullActive.length > 9600) {
                transcribeAndReport(fullActive, listener, true, epoch);
                return now;
            }
        }
        return lastPartialTime;
    }

    /**
     * Drains completed speech segments from the VAD queue: each yields one final transcription and resets
     * {@code buffer} for the next utterance.
     */
    private void processCompletedSegments(Vad vad, List<float[]> buffer, STTListener listener, long epoch) {
        while (!vad.empty()) {
            float[] segment = vad.front().getSamples();
            transcribeAndReport(segment, listener, false, epoch);
            vad.pop();
            buffer.clear();
        }
    }

    /** Concatenates float chunks into one array for decoding. */
    private float[] flatten(List<float[]> chunks) {
        int totalLength = 0;
        for (float[] chunk : chunks) totalLength += chunk.length;
        float[] result = new float[totalLength];
        int pos = 0;
        for (float[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.length);
            pos += chunk.length;
        }
        return result;
    }

    /**
     * Runs offline recognition if the sample count is sufficient and {@code epoch} still matches the live session.
     * Releases the native stream in a {@code finally} block.
     * 
     * Note: Both recognizers use greedy search (sherpa-onnx limitation for Whisper).
     *
     * @param isPartial {@code true} for previews during speech, {@code false} for VAD-finalized segments
     */
    private void transcribeAndReport(float[] samples, STTListener listener, boolean isPartial, long epoch) {
        if (samples.length < 1600) return;

        // Choose recognizer based on partial vs final
        OfflineRecognizer recognizer = isPartial ? greedyRecognizer : beamRecognizer;
        
        OfflineStream stream = null;
        try {
            if (epoch != sttEpoch.get()) {
                return;
            }

            long t0 = System.nanoTime();
            
            stream = recognizer.createStream();
            stream.acceptWaveform(samples, ModelManager.STT_SAMPLE_RATE);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText().trim();

            long decodeMs = (System.nanoTime() - t0) / 1_000_000;
            
            // Log slow decodes for performance monitoring
            if (decodeMs > 500) {
                logger.log(Level.WARNING, "STT: Slow decode: {0}ms for {1} samples ({2})", 
                    new Object[]{decodeMs, samples.length, isPartial ? "partial" : "final"});
            }

            if (epoch != sttEpoch.get()) {
                return;
            }

            // Filter spurious Whisper tokens: isolated brackets, very short strings, or whitespace-only
            if (!text.isEmpty() && !isSpuriousToken(text)) {
                voiceLog.logVoice(String.format("[%s] %s (decode: %dms)", 
                    isPartial ? "PARTIAL" : "FINAL", text, decodeMs));
                if (isPartial) {
                    listener.onPartialResult(text);
                } else {
                    logger.log(Level.INFO, "STT Final: {0} (greedy search, {1}ms)", new Object[]{text, decodeMs});
                    listener.onResult(text);
                }
            } else if (!text.isEmpty()) {
                logger.log(Level.FINE, "STT: Filtered spurious token: ''{0}''", text);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "STT: Transcription error", e);
        } finally {
            if (stream != null) {
                stream.release();
            }
        }
    }

    /**
     * Detects spurious tokens that Whisper sometimes generates when context is insufficient.
     * Common patterns: isolated brackets "[", "]", very short strings, or punctuation-only.
     *
     * @param text transcribed text to validate
     * @return {@code true} if the text should be filtered out
     */
    private boolean isSpuriousToken(String text) {
        // Single character tokens (except valid single letters in some languages)
        if (text.length() == 1) {
            char c = text.charAt(0);
            // Allow single letters that could be valid words (e.g., "I", "a")
            return !Character.isLetterOrDigit(c);
        }
        
        // Isolated brackets or common Whisper artifacts
        return text.equals("[") || text.equals("]") || 
               text.equals("(") || text.equals(")") ||
               text.equals("...") || text.equals("...");
    }

    /**
     * Stops capture and processing and invalidates in-flight transcription for this session by bumping the epoch.
     */
    @Override
    public void stopListening() {
        synchronized (this) {
            listening = false;
            sttEpoch.incrementAndGet();
        }
    }

    /** Delegates to {@link #close()}. */
    public void shutdown() {
        close();
    }

    /**
     * Stops listening, shuts down worker threads, and releases both recognizers. Idempotent with respect to
     * stopping capture; do not call {@link #startListening(STTListener)} after this without constructing a new
     * service (the executor is terminated).
     */
    @Override
    public void close() {
        stopListening();
        workerPool.shutdownNow();
        // Only release once since both references point to the same recognizer
        if (greedyRecognizer != null) greedyRecognizer.release();
        logger.info("STT: Service shut down.");
    }
}
