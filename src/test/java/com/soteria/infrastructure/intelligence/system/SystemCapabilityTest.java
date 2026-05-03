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
    @DisplayName("Perfil persistido E2B (legado) se mapea a LITE")
    void legacyE2BNameMapsToLite() {
        assertEquals(SystemCapability.AIModelProfile.LITE, SystemCapability.parseStoredProfile("E2B"));
    }

    @Test
    @DisplayName("Umbral LITE / Gemma 4 E2B (< 6GB)")
    void liteThreshold() {
        SystemCapability cap2 = new SystemCapability(2L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.LITE, cap2.getRecommendedProfile());

        SystemCapability cap5 = new SystemCapability(5L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.LITE, cap5.getRecommendedProfile());
    }

    @Test
    @DisplayName("Umbral STABLE E4B Q4 (6–12GB)")
    void stableThreshold() {
        SystemCapability cap6 = new SystemCapability(6L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.STABLE, cap6.getRecommendedProfile());

        SystemCapability cap8 = new SystemCapability(8L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.STABLE, cap8.getRecommendedProfile());

        SystemCapability cap11 = new SystemCapability(11L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.STABLE, cap11.getRecommendedProfile());
    }

    @Test
    @DisplayName("Umbral EXPERT (>= 12GB)")
    void expertThreshold() {
        SystemCapability capability = new SystemCapability(16L * 1024 * 1024 * 1024);
        assertEquals(SystemCapability.AIModelProfile.EXPERT, capability.getRecommendedProfile());
        assertFalse(capability.isLowPowerDevice());
    }

    @Test
    @DisplayName("Cálculo de hilos óptimos para evitar lag")
    void idealThreadCount() {
        SystemCapability capability = new SystemCapability(32L * 1024 * 1024 * 1024);
        int logicalCores = Runtime.getRuntime().availableProcessors();
        
        int expected = Math.max(1, logicalCores / 2);
        assertEquals(expected, capability.getIdealThreadCount());
    }

    @Test
    @DisplayName("Cálculo de hilos para dispositivos de baja potencia")
    void idealThreadCountLowPower() {
        SystemCapability capability = new SystemCapability(1L * 1024 * 1024 * 1024);
        int logicalCores = Runtime.getRuntime().availableProcessors();
        
        int expected = Math.clamp(logicalCores / 2, 1, 4);
        assertEquals(expected, capability.getIdealThreadCount());
    }
}
