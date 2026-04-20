package com.soteria.infrastructure.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

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
}
