package com.soteria.infrastructure.intelligence.triage;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.Triage;
import com.soteria.core.port.Triage.Intent;
import com.soteria.core.port.Triage.TriageResult;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance triage service for intent classification.
 * Uses a Small Language Model to dynamically classify messages based on
 * protocol candidates.
 * NO HARDCODED LANGUAGE - NO CATEGORY ANCHORS.
 */
public class TriageService implements AutoCloseable, Triage {
    private static final Logger logger = Logger.getLogger(TriageService.class.getName());
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String LOG_INPUT = "classifier_input.log";
    private static final String LOG_OUTPUT = "classifier_output.log";

    private final Path modelFile;
    private LlamaModel model;
    private final Map<String, float[]> protocolVectorCache = new ConcurrentHashMap<>();
    private float[] centroid;

    public TriageService(Path modelFile) {
        this.modelFile = modelFile;
        initializeModel();
        setupClassifierLogging();
    }

    private void initializeModel() {
        try {
            ModelParameters params = new ModelParameters()
                    .setModel(modelFile.toAbsolutePath().toString())
                    .setGpuLayers(0)
                    .enableEmbedding();
            this.model = new LlamaModel(params);
            logger.info("TriageService: Semantic model initialized successfully.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "TriageService: Failed to initialize semantic model", e);
        }
    }

    private void setupClassifierLogging() {
        try {
            Path dir = Paths.get("logs", "raw_classifier");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // Use APPEND instead of TRUNCATE to preserve logs from multiple runs/instances
            String sessionStart = String.format("%n--- SESSION START: %s ---%n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            if (!Files.exists(dir.resolve(LOG_INPUT))) {
                Files.writeString(dir.resolve(LOG_INPUT),
                        "--- SoterIA Raw Classifier Input (Autocleaned) ---\n" + sessionStart,
                        StandardOpenOption.CREATE);
            } else {
                Files.writeString(dir.resolve(LOG_INPUT), sessionStart, StandardOpenOption.APPEND);
            }

            if (!Files.exists(dir.resolve(LOG_OUTPUT))) {
                Files.writeString(dir.resolve(LOG_OUTPUT),
                        "--- SoterIA Raw Classifier Output (Autocleaned) ---\n" + sessionStart,
                        StandardOpenOption.CREATE);
            } else {
                Files.writeString(dir.resolve(LOG_OUTPUT), sessionStart, StandardOpenOption.APPEND);
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "TriageService: Failed to initialize logging system", e);
        }
    }

    /**
     * Quick classification against all protocols. 
     * Use sparingly; preferred flow is RAG-first (Retrieve and Rerank).
     */
    public String classify(String text, List<Protocol> allProtocols) {
        TriageResult result = classifyDynamic(text, allProtocols);
        return result.intent().name();
    }

    /**
     * Dynamically classifies an input against a set of protocol candidates.
     */
    public TriageResult classifyDynamic(String text, List<Protocol> candidates) {
        logRaw(LOG_INPUT, "DYNAMIC_TRIAGE: " + (text == null ? "NULL" : text) 
                + " | candidates=" + (candidates != null ? candidates.size() : 0));

        if (model == null || text == null || text.isBlank() || candidates == null || candidates.isEmpty()) {
            return new TriageResult(null, 0.0f, Intent.UNKNOWN);
        }

        try {
            float[] inputVector = model.embed(text);
            float[] centeredInput = (centroid != null) ? center(inputVector) : inputVector;

            ProtocolBestMatch bestMatch = findBestProtocol(centeredInput, candidates);

            if (bestMatch.protocol != null && bestMatch.score >= 0.30f) {
                Intent intent = mapToIntent(bestMatch.protocol.getCategory());
                TriageResult result = new TriageResult(bestMatch.protocol, bestMatch.score, intent);
                logRaw(LOG_OUTPUT, String.format("Result: %s (Score: %.4f, ID: %s)", intent, bestMatch.score, bestMatch.protocol.getId()));
                return result;
            }

            return new TriageResult(null, bestMatch.score, Intent.UNKNOWN);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Dynamic triage failed", e);
            return new TriageResult(null, 0.0f, Intent.UNKNOWN);
        }
    }

    private record ProtocolBestMatch(Protocol protocol, float score) {}

    private ProtocolBestMatch findBestProtocol(float[] centeredInput, List<Protocol> candidates) {
        Protocol bestProtocol = null;
        float maxSimilarity = -1.0f;

        for (Protocol protocol : candidates) {
            float[] protocolVector = getOrCacheVector(protocol);
            float similarity = cosineSimilarity(centeredInput, protocolVector);
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                bestProtocol = protocol;
            }
        }
        return new ProtocolBestMatch(bestProtocol, maxSimilarity);
    }

    private float[] getOrCacheVector(Protocol protocol) {
        return protocolVectorCache.computeIfAbsent(protocol.getId(), id -> {
            String role = "";
            if (id.endsWith("_VIC")) {
                role = "[VIC]";
            } else if (id.endsWith("_WIT")) {
                role = "[WIT]";
            }
            String keywordsStr = protocol.getKeywords() == null ? "" : String.join(" ", protocol.getKeywords());
            String anchorText = String.format("%s %s %s", role, protocol.getTitle(), keywordsStr).trim();
            
            float[] raw = model.embed(anchorText);
            return (centroid != null) ? center(raw) : raw;
        });
    }

    private Intent mapToIntent(String category) {
        if (category == null)
            return Intent.UNKNOWN;
        return switch (category.toUpperCase()) {
            case "MEDICAL", "VITAL", "TRAUMA" -> Intent.MEDICAL_EMERGENCY;
            case "SECURITY", "CRIME", "THREAT" -> Intent.SECURITY_EMERGENCY;
            case "ENVIRONMENTAL", "FIRE", "HAZMAT" -> Intent.ENVIRONMENTAL_EMERGENCY;
            case "TRAFFIC", "ACCIDENT" -> Intent.TRAFFIC_EMERGENCY;
            default -> Intent.UNKNOWN;
        };
    }

    private float cosineSimilarity(float[] v1, float[] v2) {
        float dot = 0.0f;
        float n1 = 0.0f;
        float n2 = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            n1 += v1[i] * v1[i];
            n2 += v2[i] * v2[i];
        }
        float mag = (float) (Math.sqrt(n1) * Math.sqrt(n2));
        return (mag > 1e-9) ? (dot / mag) : 0.0f;
    }

    private void logRaw(String filename, String content) {
        try {
            Path path = Paths.get("logs", "raw_classifier", filename);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            String entry = String.format("%s | %s%n%s%n", timestamp, content, "-".repeat(50));
            Files.writeString(path, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.log(Level.FINE, e, () -> "Failed to write raw classifier log: " + filename);
        }
    }

    public void setCentroid(float[] centroid) {
        this.centroid = centroid;
        this.protocolVectorCache.clear(); // Important: clear cache as space has changed
    }

    private float[] center(float[] vector) {
        float[] result = new float[vector.length];
        float norm = 0;
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] - centroid[i];
            norm += result[i] * result[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-9) {
            for (int i = 0; i < result.length; i++)
                result[i] /= norm;
        }
        return result;
    }

    public LlamaModel getModel() {
        return model;
    }

    @Override
    public void close() {
        if (model != null) {
            model.close();
        }
    }
}
