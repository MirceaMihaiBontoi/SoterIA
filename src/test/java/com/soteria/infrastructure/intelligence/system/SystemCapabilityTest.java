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
}
