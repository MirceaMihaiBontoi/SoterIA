package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRegistryTest {

    private ProtocolRegistry registry;

    @BeforeEach
    void setUp() {
        // Points to src/test/resources/test_protocols
        registry = new ProtocolRegistry("test_protocols");
    }

    @Test
    @DisplayName("Should load protocols from valid manifest and category files")
    void testLoadProtocolsSuccess() {
        registry.loadProtocols();
        
        List<Protocol> all = registry.getProtocols();
        // 2 from category1.json + 2 from category_multilingual.json
        assertEquals(4, all.size(), "Should load all protocols from manifest");
        
        Protocol p1 = registry.getProtocolById("P001");
        assertNotNull(p1);
        assertEquals("Ataque Cardíaco", p1.getTitle());
        assertEquals("MEDICAL", p1.getCategory());
        
        Protocol pZh = registry.getProtocolById("P_ZH");
        assertNotNull(pZh);
        assertEquals("心脏骤停", pZh.getTitle());
    }

    @Test
    @DisplayName("Should maintain UTF-8 integrity for multilingual protocols")
    void testMultilingualIntegrity() {
        registry.loadProtocols();
        
        Protocol pAr = registry.getProtocolById("P_AR");
        assertNotNull(pAr);
        assertEquals("حالة طوارئ", pAr.getTitle());
        assertTrue(pAr.getKeywords().contains("حالة"));
    }

    @Test
    @DisplayName("Should handle missing manifest gracefully")
    void testMissingManifest() {
        ProtocolRegistry badRegistry = new ProtocolRegistry("non_existent_folder");
        badRegistry.loadProtocols();
        
        assertTrue(badRegistry.getProtocols().isEmpty(), "Should have no protocols if manifest is missing");
    }

    @Test
    @DisplayName("Should handle partially missing category files")
    void testPartialMissingCategories() {
        // index.json points to "non_existent.json" which doesn't exist
        registry.loadProtocols();
        
        // Should still have the 4 valid ones from category1.json and category_multilingual.json
        assertEquals(4, registry.getProtocols().size());
    }

    @Test
    @DisplayName("Should clear previous protocols on reload")
    void testReload() {
        registry.loadProtocols();
        assertEquals(4, registry.getProtocols().size());
        
        // Reloading should not duplicate
        registry.loadProtocols();
        assertEquals(4, registry.getProtocols().size());
    }

    @Test
    @DisplayName("Should return null for non-existent protocol ID")
    void testGetInvalidId() {
        registry.loadProtocols();
        assertNull(registry.getProtocolById("UNKNOWN"));
    }
}
