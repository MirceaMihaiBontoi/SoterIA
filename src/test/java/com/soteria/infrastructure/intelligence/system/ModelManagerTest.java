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
        Path path = manager.getBrainModelPath(SystemCapability.AIModelProfile.STABLE);
        
        assertTrue(path.toString().contains("gemma-4-E4B-it-Q4_K_M.gguf"));
        assertEquals(tempDir, path.getParent());

        Path lite = manager.getBrainModelPath(SystemCapability.AIModelProfile.LITE);
        assertTrue(lite.toString().contains("gemma-4-E2B-it-Q4_K_M.gguf"));
    }

    @Test
    @DisplayName("Should resolve correct Sherpa STT model path")
    void sherpaSTTModelPathResolution() {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path path = manager.getSTTModelPath();
        
        assertTrue(path.toString().contains("sherpa-onnx-whisper-small"));
        assertEquals(tempDir, path.getParent());
    }

    @Test
    @DisplayName("Should correctly identify Sherpa STT readiness")
    void sherpaSTTReadiness() throws IOException {
        ModelManager manager = new ModelManager(capability, tempDir);
        Path modelDir = manager.getSTTModelPath();
        
        assertFalse(manager.isSTTModelReady(), "Should not be ready if directory doesn't exist");
        
        Files.createDirectories(modelDir);
        Files.createFile(modelDir.resolve("small-encoder.onnx"));
        Files.createFile(modelDir.resolve("small-decoder.onnx"));
        Files.createFile(modelDir.resolve("tokens.txt"));
        
        assertTrue(manager.isSTTModelReady(), "Should be ready if directory and required files exist");
    }

}
