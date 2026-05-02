package com.soteria.infrastructure.intelligence.stt;

import com.k2fsa.sherpa.onnx.*;
import com.soteria.infrastructure.intelligence.system.AudioNormalizer;
import com.soteria.infrastructure.intelligence.system.AudioUtils;
import com.soteria.infrastructure.intelligence.system.LanguageUtils;
import com.soteria.infrastructure.intelligence.system.NativeLibraryLoader;
import com.soteria.core.port.STT;
import com.soteria.core.port.STTListener;
import com.soteria.infrastructure.intelligence.system.ModelManager;

import javax.sound.sampled.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * High-performance, multilingual STT service using sherpa-onnx Whisper and Silero VAD.
 * Eliminates O(N^2) partial transcription bottlenecks through VAD-managed segments.
 */
public class SherpaSTTService implements AutoCloseable, STT {
    private static final Logger logger = Logger.getLogger(SherpaSTTService.class.getName());

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String VOICE_LOG_DIR = "logs/voice";
    private static final String STT_LOG_FILE = "stt.log";

    private final OfflineRecognizer recognizer;
    private final ExecutorService workerPool;
    private final ModelManager modelManager;
    private final String language;

    private volatile boolean listening = false;
    /** Bumped whenever a listening session stops or restarts — drops stale transcriptions still in flight. */
    private final AtomicLong sttEpoch = new AtomicLong(0);
    private final BlockingQueue<float[]> audioQueue = new LinkedBlockingQueue<>(100);
    private final AudioNormalizer normalizer = new AudioNormalizer();

    public SherpaSTTService(Path modelPath, ModelManager modelManager) throws IOException {
        this(modelPath, "", modelManager);
    }

    public SherpaSTTService(Path modelPath, String language, ModelManager modelManager) throws IOException {
        this.modelManager = modelManager;
        this.language = LanguageUtils.isoCode(language);

        NativeLibraryLoader.load();

        this.recognizer = createRecognizer(modelPath);

        Vad probeVad = createVad();
        try {
            logger.fine("STT: Silero VAD model validated.");
        } finally {
            probeVad.release();
        }

        this.workerPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("stt-worker-" + t.hashCode());
            return t;
        });

        setupVoiceLogging();
    }

    private OfflineRecognizer createRecognizer(Path modelPath) throws IOException {
        Path encoderPath = findFileBySuffix(modelPath, "-encoder.int8.onnx", "-encoder.onnx");
        Path decoderPath = findFileBySuffix(modelPath, "-decoder.int8.onnx", "-decoder.onnx");
        Path tokensPath = findFileBySuffix(modelPath, "-tokens.txt", "tokens.txt");

        if (encoderPath == null || decoderPath == null || tokensPath == null) {
            throw new IOException("Mandatory Whisper model files missing in: " + modelPath);
        }

        OfflineWhisperModelConfig whisperConfig = OfflineWhisperModelConfig.builder()
                .setEncoder(encoderPath.toString())
                .setDecoder(decoderPath.toString())
                .setLanguage(this.language)
                .setTask("transcribe")
                .build();

        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setWhisper(whisperConfig)
                .setTokens(tokensPath.toString())
                .setNumThreads(2)
                .build();

        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(ModelManager.STT_SAMPLE_RATE)
                        .setFeatureDim(80)
                        .build())
                .setDecodingMethod("greedy_search")
                .build();

        return new OfflineRecognizer(config);
    }

    private Vad createVad() throws IOException {
        Path vadPath = modelManager.getVADModelPath();
        if (!modelManager.isVADModelReady()) {
            throw new IOException("Silero VAD model not found. Please ensure ModelManager has downloaded it.");
        }

        SileroVadModelConfig sileroConfig = SileroVadModelConfig.builder()
                .setModel(vadPath.toString())
                .setThreshold(modelManager.getSTTVadThreshold())
                .setMinSilenceDuration(modelManager.getSTTMinSilenceDuration())
                .setMinSpeechDuration(modelManager.getSTTMinSpeechDuration())
                .setWindowSize(ModelManager.VAD_WINDOW_SIZE)
                .build();

        VadModelConfig config = VadModelConfig.builder()
                .setSileroVadModelConfig(sileroConfig)
                .setSampleRate(ModelManager.STT_SAMPLE_RATE)
                .setNumThreads(1)
                .build();

        return new Vad(config);
    }

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

        workerPool.submit(() -> runAudioCapture(listener, epoch));
        workerPool.submit(() -> runProcessingLoop(listener, epoch));
    }

    private void runAudioCapture(STTListener listener, long epoch) {
        AudioFormat format = new AudioFormat(ModelManager.STT_SAMPLE_RATE,
                ModelManager.STT_BIT_DEPTH, ModelManager.STT_CHANNELS, true, false);

        try (TargetDataLine line = AudioUtils.getResilientMic(format)) {
            line.start();
            logger.info("STT: Audio capture started.");

            byte[] byteBuffer = new byte[ModelManager.VAD_WINDOW_SIZE * 2];

            while (listening && !Thread.currentThread().isInterrupted()) {
                int read = line.read(byteBuffer, 0, byteBuffer.length);
                if (read < byteBuffer.length) continue;

                normalizer.normalize(byteBuffer, read);

                float[] samples = new float[ModelManager.VAD_WINDOW_SIZE];
                for (int i = 0; i < samples.length; i++) {
                    short s = (short) ((byteBuffer[i * 2] & 0xFF) | (byteBuffer[i * 2 + 1] << 8));
                    samples[i] = s / 32768.0f;
                }

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

    private void runProcessingLoop(STTListener listener, long epoch) {
        final Vad vad;
        try {
            vad = createVad();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "STT: failed to instantiate session VAD", e);
            listener.onError(e);
            return;
        }

        try {
            long lastPartialTime = 0;
            java.util.List<float[]> activeSpeechBuffer = new java.util.ArrayList<>();

            try {
                while (listening && !Thread.currentThread().isInterrupted()) {
                    float[] samples = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (samples == null) continue;

                    vad.acceptWaveform(samples);

                    if (vad.isSpeechDetected()) {
                        lastPartialTime = handleActiveSpeech(vad, samples, activeSpeechBuffer, lastPartialTime, listener,
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

    private long handleActiveSpeech(Vad vad, float[] samples, java.util.List<float[]> buffer, long lastPartialTime,
            STTListener listener, long epoch) {
        buffer.add(samples);
        long now = System.currentTimeMillis();
        if (now - lastPartialTime > 1200) {
            float[] fullActive = flatten(buffer);
            if (fullActive.length > 4800) {
                transcribeAndReport(fullActive, listener, true, epoch);
                return now;
            }
        }
        return lastPartialTime;
    }

    private void processCompletedSegments(Vad vad, java.util.List<float[]> buffer, STTListener listener, long epoch) {
        while (!vad.empty()) {
            float[] segment = vad.front().getSamples();
            transcribeAndReport(segment, listener, false, epoch);
            vad.pop();
            buffer.clear();
        }
    }

    private float[] flatten(java.util.List<float[]> chunks) {
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

    private void transcribeAndReport(float[] samples, STTListener listener, boolean isPartial, long epoch) {
        if (samples.length < 1600) return;

        try {
            if (epoch != sttEpoch.get()) {
                return;
            }

            OfflineStream stream = recognizer.createStream();
            stream.acceptWaveform(samples, ModelManager.STT_SAMPLE_RATE);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText().trim();
            stream.release();

            if (epoch != sttEpoch.get()) {
                return;
            }

            if (!text.isEmpty()) {
                logVoice(String.format("[%s] %s", isPartial ? "PARTIAL" : "FINAL", text));
                if (isPartial) {
                    listener.onPartialResult(text);
                } else {
                    logger.log(Level.INFO, "STT Final: {0}", text);
                    listener.onResult(text);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "STT: Transcription error", e);
        }
    }

    private void setupVoiceLogging() {
        try {
            java.nio.file.Path dir = Paths.get(VOICE_LOG_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Files.writeString(dir.resolve(STT_LOG_FILE),
                    "--- SoterIA STT Raw Log (Started: " + LocalDateTime.now() + ") ---\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "STT: Failed to initialize voice logging", e);
        }
    }

    private void logVoice(String content) {
        try {
            java.nio.file.Path path = Paths.get(VOICE_LOG_DIR, STT_LOG_FILE);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            Files.writeString(path, String.format("%s | %s%n", timestamp, content),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to write STT log", e);
        }
    }

    @Override
    public void stopListening() {
        synchronized (this) {
            listening = false;
            sttEpoch.incrementAndGet();
        }
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        stopListening();
        workerPool.shutdownNow();
        if (recognizer != null) recognizer.release();
        logger.info("STT: Service shut down.");
    }

    private Path findFileBySuffix(Path directory, String... suffixes) throws IOException {
        try (var stream = java.nio.file.Files.list(directory)) {
            java.util.List<Path> files = stream.toList();
            for (String suffix : suffixes) {
                for (Path file : files) {
                    if (file.getFileName().toString().endsWith(suffix)) return file;
                }
            }
        }
        return null;
    }
}
