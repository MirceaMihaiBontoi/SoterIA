package com.soteria.infrastructure.intelligence;

import com.soteria.core.port.STTListener;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance, offline Speech-to-Text service using Vosk.
 */
public class VoskSTTService implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(VoskSTTService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private Model model;
    private Recognizer recognizer;
    private TargetDataLine line;
    private ExecutorService executor;
    private volatile boolean listening = false;
    private final java.util.concurrent.CountDownLatch shutdownLatch = new java.util.concurrent.CountDownLatch(1);

    public VoskSTTService(Path modelPath) throws IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        logger.log(Level.INFO, "Loading Vosk model from: {0}", modelPath);
        this.model = new Model(modelPath.toString());
        this.recognizer = new Recognizer(model, 16000.0f);
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void startListening(STTListener listener) {
        if (listening)
            return;

        listening = true;
        executor.submit(() -> {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                listener.onError(new RuntimeException("Microphone or requested format not supported"));
                return;
            }

            try {
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                byte[] buffer = new byte[4096];
                int bytesRead;

                logger.info("STT Service: Listening...");

                while (listening) {
                    bytesRead = line.read(buffer, 0, buffer.length);
                    if (bytesRead < 0)
                        break;

                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        String rawResult = recognizer.getResult();
                        listener.onResult(extractText(rawResult));
                    } else {
                        String rawPartial = recognizer.getPartialResult();
                        listener.onPartialResult(extractPartial(rawPartial));
                    }
                }

                line.stop();
                line.close();
                logger.info("STT Service: Stopped.");

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error in STT audio loop", e);
                listener.onError(e);
            } finally {
                listening = false;
                shutdownLatch.countDown();
            }
        });
    }

    public void stopListening() {
        listening = false;
        if (line != null && line.isOpen()) {
            line.stop();
        }
    }

    static String extractText(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has("text") ? node.get("text").asText() : "";
        } catch (Exception _) {
            return "";
        }
    }

    static String extractPartial(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has("partial") ? node.get("partial").asText() : "";
        } catch (Exception _) {
            return "";
        }
    }

    public void shutdown() {
        stopListening();
        executor.shutdownNow();
        try {
            // Give the background thread a moment to finish before freeing native memory
            if (!shutdownLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warning("Vosk listener background thread did not terminate within timeout.");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        try {
            if (recognizer != null)
                recognizer.close();
            if (model != null)
                model.close();
        } catch (Exception _) {
            // Silently swallow native memory access errors during shutdown
            logger.log(Level.FINE, "Silent failure during Vosk memory cleanup");
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
