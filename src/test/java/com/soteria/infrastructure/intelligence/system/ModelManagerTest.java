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
    @DisplayName("Should resolve correct Sherpa STT model path")
    void sherpaSTTModelPathResolution() {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path path = manager.getSTTModelPath();
        
        assertTrue(path.toString().contains("sherpa-onnx-whisper-base"));
        assertEquals(tempDir, path.getParent());
    }

    @Test
    @DisplayName("Should correctly identify Sherpa STT readiness")
    void sherpaSTTReadiness() throws IOException {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path modelDir = manager.getSTTModelPath();
        
        assertFalse(manager.isSTTModelReady(), "Should not be ready if directory doesn't exist");
        
        Files.createDirectories(modelDir);
        Files.createFile(modelDir.resolve("base-encoder.onnx"));
        assertTrue(manager.isSTTModelReady(), "Should be ready if directory and encoder file exist");
    }

}
