package com.soteria.infrastructure.intelligence;

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
        Path path = manager.getBrainModelPath(SystemCapability.AIModelProfile.LITE, null);
        
        assertTrue(path.toString().contains("gemma-3-1b-it-Q8_0.gguf"));
        assertEquals(tempDir, path.getParent());
    }

    @Test
    @DisplayName("Should resolve custom brain model path from URL")
    void customBrainModelPath() {
        ModelManager manager = new ModelManager(capability, tempDir);
        String customUrl = "https://example.com/model.gguf";
        Path path = manager.getBrainModelPath(SystemCapability.AIModelProfile.BALANCED, customUrl);
        
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
    @DisplayName("Should select correct URL based on capability")
    void capabilityBasedUrls() {
        // Balanced on 16GB
        ModelManager manager = new ModelManager(capability, tempDir);
        String url = manager.getBrainModelUrl(SystemCapability.AIModelProfile.BALANCED);
        assertTrue(url.contains("gemma-3-4b-it-Q4_K_M.gguf"));

        // Vosk Perf on 16GB
        String voskUrl = manager.getVoskModelUrl(SPANISH);
        assertTrue(voskUrl.contains("vosk-model-es-0.42.zip"), "Should use full model for 16GB");

        // Vosk Lite on 4GB
        SystemCapability lowCap = new SystemCapability(4096);
        ModelManager managerLow = new ModelManager(lowCap, tempDir);
        String voskUrlLow = managerLow.getVoskModelUrl(SPANISH);
        assertTrue(voskUrlLow.contains("vosk-model-small-es-0.42.zip"), "Should use small model for 4GB");
    }
}
