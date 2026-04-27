package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.infrastructure.intelligence.system.SystemCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticEngineTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should compute, save and load centroid correctly")
    void testCentroidLifecycle() throws Exception {
        SystemCapability capability = new SystemCapability(8L * 1024 * 1024 * 1024); // 8GB
        float[] expected = { 0.5f, 0.5f, 0.0f };

        try (SemanticEngine engine = new SemanticEngine(capability, tempDir)) {
            float[] v1 = { 1.0f, 0.0f, 0.0f };
            float[] v2 = { 0.0f, 1.0f, 0.0f };

            engine.computeAndSaveCentroid(Arrays.asList(v1, v2));
            assertArrayEquals(expected, engine.getCentroid(), 1e-6f);

            // Verify file exists
            assertTrue(tempDir.resolve("centroid.bin").toFile().exists());
        }

        // Test loading in a fresh engine
        try (SemanticEngine engine2 = new SemanticEngine(capability, tempDir)) {
            assertTrue(engine2.loadCentroid());
            assertArrayEquals(expected, engine2.getCentroid(), 1e-6f);
        }
    }

    @Test
    @DisplayName("Should handle missing or corrupt centroid file")
    void testCentroidErrorHandling() throws Exception {
        SystemCapability capability = new SystemCapability(8L * 1024 * 1024 * 1024);
        try (SemanticEngine engine = new SemanticEngine(capability, tempDir)) {
            // Load non-existent
            assertFalse(engine.loadCentroid());
            assertNull(engine.getCentroid());
        }
    }

    @Test
    @DisplayName("Should apply mean-centering and normalization during query embedding using test hook")
    void testEmbedQueryWithCentroid() throws Exception {
        SystemCapability capability = new SystemCapability(8L * 1024 * 1024 * 1024);
        try (SemanticEngine engine = new SemanticEngine(capability, tempDir)) {
            // Use the built-in VectorEmbedder hook
            engine.setTestEmbedder(text -> new float[] { 1.0f, 1.0f, 0.0f });

            engine.computeAndSaveCentroid(List.of(new float[] { 0.5f, 0.5f, 0.0f }));

            // embedQuery should do: (raw - centroid) -> normalize
            // (1.0, 1.0, 0.0) - (0.5, 0.5, 0.0) = (0.5, 0.5, 0.0)
            // normalized (0.5, 0.5, 0.0) = (0.7071, 0.7071, 0.0)

            float[] result = engine.embedQuery("test query");

            float norm = (float) Math.sqrt(0.5 * 0.5 + 0.5 * 0.5);
            assertEquals(0.5f / norm, result[0], 1e-6f);
            assertEquals(0.5f / norm, result[1], 1e-6f);
            assertEquals(0.0f, result[2], 1e-6f);
        }
    }
}
