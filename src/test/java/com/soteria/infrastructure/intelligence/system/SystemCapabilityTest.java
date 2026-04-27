package com.soteria.infrastructure.intelligence.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemCapabilityTest {

    @Test
    @DisplayName("La detección identifica RAM y propone un perfil")
    void detectionIdentifiesRAM() {
        SystemCapability capability = new SystemCapability();
        
        assertNotNull(capability.getRecommendedProfile());
        assertTrue(capability.getTotalMemory() > 0);
        assertTrue(capability.getAvailableProcessors() > 0);
    }

    @Test
    @DisplayName("Umbral STABLE (< 12GB)")
    void balancedThreshold() {
        // 2GB (Now falls back to STABLE)
        SystemCapability capability2 = new SystemCapability(2L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.STABLE, capability2.getRecommendedProfile());
        
        // 8GB
        SystemCapability capability8 = new SystemCapability(8L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.STABLE, capability8.getRecommendedProfile());
    }


    @Test
    @DisplayName("Umbral EXPERT (>= 12GB)")
    void ultraThreshold() {
        // 16GB
        SystemCapability capability = new SystemCapability(16L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.EXPERT, capability.getRecommendedProfile());
        assertFalse(capability.isLowPowerDevice());
    }

    @Test
    @DisplayName("Cálculo de hilos óptimos para evitar lag")
    void idealThreadCount() {
        // Simulamos un perfil potente (ULTRA)
        SystemCapability capability = new SystemCapability(32L * 1024 * 1024 * 1024);
        int logicalCores = Runtime.getRuntime().availableProcessors();
        
        // El punto dulce es la mitad (asumiendo SMT)
        int expected = Math.max(1, logicalCores / 2);
        assertEquals(expected, capability.getIdealThreadCount());
    }

    @Test
    @DisplayName("Cálculo de hilos para dispositivos de baja potencia")
    void idealThreadCountLowPower() {
        // Perfil bajo (1GB RAM - still STABLE now)
        SystemCapability capability = new SystemCapability(1L * 1024 * 1024 * 1024);
        int logicalCores = Runtime.getRuntime().availableProcessors();
        
        // En dispositivos móviles se capa más agresivamente
        int expected = Math.clamp(logicalCores / 2, 1, 4);
        assertEquals(expected, capability.getIdealThreadCount());
    }
}
