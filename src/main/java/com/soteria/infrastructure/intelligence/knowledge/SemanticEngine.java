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

    private LlamaModel embedder;
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

    public float[] getCentroid() {
        return centroid;
    }

    public float[] embedQuery(String queryText) {
        if (embedder == null) return new float[0];
        float[] raw = embedder.embed(queryText);
        if (centroid != null) {
            return VectorMath.normalize(VectorMath.subtract(raw, centroid));
        }
        return raw;
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

    public void loadCentroid() {
        try {
            Path cPath = indexPath.resolve("centroid.bin");
            if (!Files.exists(cPath)) {
                logger.warning("[RAG-CENTROID] sidecar missing, mean-centering disabled for this run");
                return;
            }
            try (java.io.DataInputStream in = new java.io.DataInputStream(Files.newInputStream(cPath))) {
                int dims = in.readInt();
                centroid = new float[dims];
                for (int i = 0; i < dims; i++) {
                    centroid[i] = in.readFloat();
                }
                logger.log(Level.INFO, "Loaded semantic centroid from: {0} (dims: {1})", new Object[]{cPath, dims});
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not load centroid: {0}", e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        if (embedder != null) {
            embedder.close();
        }
    }
}
