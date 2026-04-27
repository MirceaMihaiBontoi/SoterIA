package com.soteria.infrastructure.intelligence.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelManager Tests")
class ModelManagerTest {

    @TempDir
    Path tempDir;

    private static final String SPANISH = "Spanish";

    private SystemCapability capability;

    @BeforeEach
    void setUp() {
        // Use a 16GB RAM capability for balanced profile testing
        capability = new SystemCapability(16L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("Should resolve correct brain model path for profile")
    void brainModelPathResolution() {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path path = manager.getBrainModelPath(SystemCapability.AIModelProfile.STABLE, null);
        
        assertTrue(path.toString().contains("gemma-3-4b-it-Q4_K_M.gguf"));
        assertEquals(tempDir, path.getParent());
    }

    @Test
    @DisplayName("Should resolve custom brain model path from URL")
    void customBrainModelPath() {
        ModelManager manager = new ModelManager(capability, tempDir);
        String customUrl = "https://example.com/model.gguf";
        Path path = manager.getBrainModelPath(SystemCapability.AIModelProfile.STABLE, customUrl);
        
        assertTrue(path.getFileName().toString().startsWith("custom-"));
        assertTrue(path.getFileName().toString().endsWith(".gguf"));
    }

    @Test
    @DisplayName("Should resolve correct Vosk model path for language")
    void voskModelPathResolution() {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path path = manager.getVoskModelPath(SPANISH);
        
        assertTrue(path.toString().contains("vosk-model-es"));
        assertEquals(tempDir, path.getParent());
    }

    @Test
    @DisplayName("Should correctly identify ready models")
    void modelReadiness() throws IOException {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path modelFile = manager.getBrainModelPath();
        
        assertFalse(manager.isBrainModelReady(), "Should not be ready if file doesn't exist");
        
        Files.createFile(modelFile);
        assertTrue(manager.isBrainModelReady(), "Should be ready if file exists");
    }

    @Test
    @DisplayName("Should resolve correct Triage and Embedding model paths")
    void triageAndEmbeddingPathResolution() {
        ModelManager manager = new ModelManager(capability, tempDir);
        
        Path triagePath = manager.getTriageModelPath();
        Path embeddingPath = manager.getEmbeddingModelPath();
        
        assertEquals(triagePath, embeddingPath, "Triage and Embedding models should currently share the same file");
        assertTrue(triagePath.getFileName().toString().endsWith(".gguf"));
        assertEquals(tempDir, triagePath.getParent());
    }

    @Test
    @DisplayName("Should handle internet dominant languages for STT models")
    void internetDominantLanguagesSTT() {
        ModelManager manager = new ModelManager(capability, tempDir);
        
        String[] languages = {"Chinese", "Arabic", "French", "German", "Japanese", "Russian", "Hindi"};
        
        for (String lang : languages) {
            String url = manager.getVoskModelUrl(lang);
            String name = manager.getVoskModelName(lang);
            
            assertNotNull(url, "URL should not be null for " + lang);
            assertNotNull(name, "Name should not be null for " + lang);
            // Currently, many will fallback to English due to hardcoding in implementation.
            // This test captures the current behavior which we will improve.
        }
    }

    @Test
    @DisplayName("Should handle diverse linguistic families for STT models")
    void linguisticFamiliesSTT() {
        ModelManager manager = new ModelManager(capability, tempDir);
        
        // Representative languages from major families
        String[] families = {"Indonesian", "Telugu", "Turkish", "Finnish", "Swahili", "Korean"};
        
        for (String lang : families) {
            Path path = manager.getVoskModelPath(lang);
            assertNotNull(path);
            assertTrue(path.toString().contains(tempDir.toString()));
        }
    }
}
