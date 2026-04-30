package com.soteria.infrastructure.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import com.soteria.infrastructure.intelligence.system.SystemCapability;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapServiceTest {

    private BootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new BootstrapService();
    }

    @Test
    @DisplayName("Estado inicial es correcto")
    void initialStateIsCorrect() {
        assertEquals("Idle", bootstrapService.statusProperty().get());
        assertEquals(0.0, bootstrapService.progressProperty().get());
        assertFalse(bootstrapService.readyProperty().get());
    }

    @Test
    @DisplayName("ready() devuelve un futuro no completado al inicio")
    void readyFutureIsNotCompletedAtStart() {
        CompletableFuture<Void> ready = bootstrapService.ready();
        assertNotNull(ready);
        assertFalse(ready.isDone());
    }

    @Test
    @DisplayName("Should accept provisioning in multiple internet-dominant languages")
    void internetDominantLanguagesBootstrap() {
        String[] languages = {"English", "Chinese", "Spanish", "Arabic", "Portuguese", 
                             "French", "Japanese", "Russian", "German", "Hindi"};
        
        for (String lang : languages) {
            // We just test if startProvisioning accepts the input without throwing
            // as full initialization requires native binaries/models
            assertDoesNotThrow(() -> 
                bootstrapService.startProvisioning(SystemCapability.AIModelProfile.STABLE, lang, null),
                "Failed to initiate bootstrap for " + lang
            );
        }
    }

    @Test
    @DisplayName("Should support provisioning for major linguistic families")
    void linguisticFamiliesBootstrap() {
        java.util.Map<String, String> families = java.util.Map.of(
            "Indo-European", "Bengali",
            "Sino-Tibetan", "Burmese",
            "Afroasiatic", "Amharic",
            "Austronesian", "Indonesian",
            "Dravidian", "Telugu",
            "Turkic", "Turkish",
            "Uralic", "Hungarian",
            "Niger-Congo", "Swahili",
            "Japonic", "Japanese",
            "Koreanic", "Korean"
        );

        families.forEach((family, lang) -> {
            assertDoesNotThrow(() -> 
                bootstrapService.startProvisioning(SystemCapability.AIModelProfile.STABLE, lang, null),
                "Failed to initiate bootstrap for " + lang + " (" + family + ")"
            );
        });
    }
}
