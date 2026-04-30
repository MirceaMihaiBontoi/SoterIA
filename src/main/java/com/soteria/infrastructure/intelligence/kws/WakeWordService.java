package com.soteria.infrastructure.intelligence.kws;

import com.k2fsa.sherpa.onnx.*;
import com.soteria.infrastructure.intelligence.system.AudioNormalizer;
import com.soteria.infrastructure.intelligence.system.AudioUtils;
import com.soteria.infrastructure.intelligence.system.NativeLibraryLoader;
import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Paths;

/**
 * High-performance, offline Keyword Spotting (Wake-Word) service using sherpa-onnx.
 * Runs continuously in the background.
 */
public class WakeWordService implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(WakeWordService.class.getName());
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String VOICE_LOG_DIR = "logs/voice";
    private static final String KWS_LOG_FILE = "kws.log";

    private final KeywordSpotter spotter;
    private final ExecutorService executor;
    private TargetDataLine line;
    private volatile boolean listening = false;
    private final AtomicReference<Runnable> activeListener = new AtomicReference<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AudioNormalizer normalizer = new AudioNormalizer();

    public WakeWordService(Path modelPath) throws IOException {
        NativeLibraryLoader.load();
        logger.log(Level.INFO, "Loading KWS model from: {0}", modelPath);

        Path keywordsFile = modelPath.resolve("keywords_raw.txt");
        // Universal multilingual variants for "SoterIA" - NO COMMENTS ALLOWED (causes native crash)
        String defaultKeywords = """
                S OW1 T EH1 R IY0 AH0 @0.015
                s o t e r i a @0.015
                s o t e l i a @0.02
                z o t e r i a @0.02
                z o t e l i a @0.02
                s o t e i a @0.03
                s o t e r i @0.02
                s o t e r i e @0.03
                s o t e r @0.04
                s o t a r i a @0.04
                s o t e r i a @0.01
                """.trim();
        Files.writeString(keywordsFile, defaultKeywords, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        long t0 = System.nanoTime();

        OnlineTransducerModelConfig transducerConfig = OnlineTransducerModelConfig.builder()
                .setEncoder(modelPath.resolve("encoder-epoch-13-avg-2-chunk-16-left-64.onnx").toString())
                .setDecoder(modelPath.resolve("decoder-epoch-13-avg-2-chunk-16-left-64.onnx").toString())
                .setJoiner(modelPath.resolve("joiner-epoch-13-avg-2-chunk-8-left-64.onnx").toString())
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducerConfig)
                .setTokens(modelPath.resolve("tokens.txt").toString())
                .setNumThreads(1)
                .setDebug(false)
                .build();

        KeywordSpotterConfig config = KeywordSpotterConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setKeywordsFile(keywordsFile.toString())
                .setKeywordsScore(2.0f) // Doubled the score bonus for SoterIA
                .setKeywordsThreshold(0.01f) // Keep it low for maximum sensitivity
                .setMaxActivePaths(10) // More context for the search
                .build();

        this.spotter = new KeywordSpotter(config);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        logger.info(() -> String.format("[TIMING] KWS Model Load: %d ms", loadMs));

        this.executor = Executors.newSingleThreadExecutor();
        setupVoiceLogging();
    }

    public void startListening(Runnable onWakeWordDetected) {
        logger.info("WakeWordService: Registering/Updating listener.");
        this.activeListener.set(onWakeWordDetected);
        
        if (listening) {
            logger.info("WakeWordService: Already in loop, listener updated.");
            return;
        }

        listening = true;
        executor.submit(this::runKeywordDetectionLoop);
    }

    private void runKeywordDetectionLoop() {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);

        try {
            this.line = AudioUtils.getResilientMic(format);
            this.line.start();

            byte[] buffer = new byte[3200];
            float[] floatBuffer = new float[buffer.length / 2];

            logger.info("KWS Service: Listening in background for 'SoterIA'...");
            OnlineStream stream = spotter.createStream();

            int loopCounter = 0;
            while (listening) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead < 0) {
                    break;
                }

                // Apply automatic gain control / normalization
                normalizer.normalize(buffer, bytesRead);

                float maxAmp = convertToFloat(buffer, bytesRead, floatBuffer);
                
                stream.acceptWaveform(floatBuffer, 16000);
                while (spotter.isReady(stream)) {
                    spotter.decode(stream);
                }

                checkKeywordResult(stream);

                if (++loopCounter % 20 == 0) {
                    logVoice(String.format("Monitor: [Gain: %.2fx] [MaxAmp: %.5f]", 
                        normalizer.getCurrentGain(), maxAmp));
                }
            }

            stream.release();
            line.stop();
            line.close();
            logger.info("KWS Service: Stopped.");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in KWS audio loop", e);
        } finally {
            listening = false;
            shutdownLatch.countDown();
        }
    }

    private float convertToFloat(byte[] buffer, int bytesRead, float[] floatBuffer) {
        float maxAmp = 0;
        for (int i = 0, j = 0; i < bytesRead; i += 2, j++) {
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            float v = sample / 32768.0f;
            
            floatBuffer[j] = v;
            maxAmp = Math.max(maxAmp, Math.abs(v));
        }
        return maxAmp;
    }

    private void checkKeywordResult(OnlineStream stream) {
        KeywordSpotterResult result = spotter.getResult(stream);
        String keyword = result.getKeyword();
        String[] tokens = result.getTokens();
        
        // Always log if we hear something (tokens) to show "what it's actually detecting"
        if (tokens != null && tokens.length > 0) {
            String hypothesis = String.join(" ", tokens);
            logVoice("Hypothesis: [ " + hypothesis + " ]");
        }

        if (keyword != null && !keyword.isEmpty()) {
            String msg = ">>> !!! KWS TRIGGERED: '" + keyword + "' !!! <<<";
            logVoice(msg);
            logger.log(Level.INFO, "\n{0}\n", msg);
            Runnable listener = activeListener.get();
            if (listener != null) {
                listener.run();
            }
            spotter.reset(stream);
        }
    }

    private void setupVoiceLogging() {
        try {
            java.nio.file.Path dir = Paths.get(VOICE_LOG_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Files.writeString(dir.resolve(KWS_LOG_FILE),
                    "--- SoterIA KWS Raw Log (Started: " + LocalDateTime.now() + ") ---\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "KWS: Failed to initialize voice logging", e);
        }
    }

    private void logVoice(String content) {
        try {
            java.nio.file.Path path = Paths.get(VOICE_LOG_DIR, KWS_LOG_FILE);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            Files.writeString(path, String.format("%s | %s%n", timestamp, content),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to write KWS log", e);
        }
    }

    public void stopListening() {
        listening = false;
        if (line != null) {
            try {
                line.stop();
                line.flush();
            } catch (Exception _) {
                // Ignore failure during force stop
            }
        }
    }

    public void shutdown() {
        stopListening();
        executor.shutdownNow();
        try {
            if (!shutdownLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warning("KWS listener background thread did not terminate within timeout.");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        try {
            if (spotter != null) {
                spotter.release();
            }
        } catch (Exception _) {
            logger.log(Level.FINE, "Silent failure during KWS memory cleanup");
        }

    }

    @Override
    public void close() {
        shutdown();
    }
}
