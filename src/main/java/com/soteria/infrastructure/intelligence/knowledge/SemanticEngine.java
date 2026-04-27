package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.infrastructure.intelligence.system.SystemCapability;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the semantic embedding logic and Llama model integration.
 */
public class SemanticEngine implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(SemanticEngine.class.getName());

    @FunctionalInterface
    public interface VectorEmbedder {
        float[] embed(String text);
    }

    private LlamaModel embedder;
    private VectorEmbedder testEmbedder;
    private float[] centroid;
    private final Path indexPath;
    private final SystemCapability capability;

    public SemanticEngine(SystemCapability capability, Path indexPath) {
        this.capability = capability;
        this.indexPath = indexPath;
    }

    public void loadEmbedder(Path modelPath) {
        try {
            int threads = capability.getIdealThreadCount();
            ModelParameters params = new ModelParameters()
                    .setModel(modelPath.toAbsolutePath().toString())
                    .setThreads(threads)
                    .setThreadsBatch(threads)
                    .setGpuLayers(-1)
                    .enableEmbedding();
            this.embedder = new LlamaModel(params);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load llama model for embeddings", e);
        }
    }

    public LlamaModel getEmbedder() {
        return embedder;
    }

    public void setEmbedder(LlamaModel embedder) {
        this.embedder = embedder;
    }

    /**
     * Injects a test-only embedder to avoid native library issues during CI/CD.
     */
    public void setTestEmbedder(VectorEmbedder testEmbedder) {
        this.testEmbedder = testEmbedder;
    }

    public boolean isEmbedderAvailable() {
        return testEmbedder != null || embedder != null;
    }

    public float[] getCentroid() {
        return centroid;
    }

    public float[] embedQuery(String queryText) {
        float[] raw;
        if (testEmbedder != null) {
            raw = testEmbedder.embed(queryText);
        } else if (embedder != null) {
            raw = embedder.embed(queryText);
        } else {
            return new float[0];
        }
        return processRawVector(raw);
    }

    private float[] processRawVector(float[] raw) {
        if (centroid != null) {
            return VectorMath.normalize(VectorMath.subtract(raw, centroid));
        }
        return VectorMath.normalize(raw);
    }

    public void computeAndSaveCentroid(List<float[]> vectors) {
        if (vectors.isEmpty()) return;
        this.centroid = VectorMath.computeCentroid(vectors);
        if (centroid != null) {
            saveCentroid();
        }
    }

    private void saveCentroid() {
        if (centroid == null) return;
        try {
            Path cPath = indexPath.resolve("centroid.bin");
            Files.createDirectories(cPath.getParent());
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    Files.newOutputStream(cPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                out.writeInt(centroid.length);
                for (float f : centroid) {
                    out.writeFloat(f);
                }
            }
            logger.log(Level.INFO, "Semantic centroid saved to: {0} (dims: {1})", new Object[]{cPath, centroid.length});
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save centroid: {0}", e.getMessage());
        }
    }

    public boolean loadCentroid() {
        Path cPath = indexPath.resolve("centroid.bin");
        if (!Files.exists(cPath)) {
            logger.warning("[RAG-CENTROID] sidecar missing, mean-centering disabled for this run");
            return false;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(Files.newInputStream(cPath))) {
            int dims = in.readInt();
            // Header sanity: prevent NegativeArraySizeException / OOM from a corrupt file.
            // 8192 covers any embedding model we ship; corrupt headers tend to be huge or negative.
            if (dims <= 0 || dims > 8192) {
                logger.log(Level.WARNING, "[RAG-CENTROID] invalid header dims={0}, deleting corrupt sidecar", dims);
                Files.deleteIfExists(cPath);
                return false;
            }
            float[] loaded = new float[dims];
            for (int i = 0; i < dims; i++) {
                loaded[i] = in.readFloat();
            }
            centroid = loaded;
            logger.log(Level.INFO, "Loaded semantic centroid from: {0} (dims: {1})", new Object[]{cPath, dims});
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not load centroid (corrupt or truncated): {0}", e.getMessage());
            try { Files.deleteIfExists(cPath); } catch (IOException _) { /* best-effort cleanup */ }
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        if (embedder != null) {
            embedder.close();
        }
    }
}
