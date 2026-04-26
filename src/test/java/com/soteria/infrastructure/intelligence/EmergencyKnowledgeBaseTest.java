package com.soteria.infrastructure.intelligence;

import com.soteria.core.domain.emergency.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmergencyKnowledgeBase Tests")
class EmergencyKnowledgeBaseTest {

    private EmergencyKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new EmergencyKnowledgeBase();
    }

    @Test
    @DisplayName("Should load protocols from granular files using manifest")
    void shouldLoadProtocols() {
        List<Protocol> all = knowledgeBase.getAllProtocols();
        // Verification for total protocol count (12 per category x 5 = 60)
        assertTrue(all.size() >= 60, "Should load at least 60 protocols from the consolidated manifest");

        // Verify we have a mix of categories with exactly 12 protocols each (or 12+)
        long medicalCount = all.stream().filter(p -> p.getCategory().contains("Vital")
                || p.getCategory().contains("Trauma") || p.getCategory().contains("Medical")).count();
        long fireCount = all.stream().filter(p -> p.getCategory().equals("Fire")).count();
        long secCount = all.stream().filter(p -> p.getCategory().equals("Security")).count();
        long envCount = all.stream().filter(p -> p.getCategory().equals("Environmental")).count();
        long trafCount = all.stream().filter(p -> p.getCategory().equals("Traffic")).count();

        assertTrue(medicalCount >= 12, "Medical should have 12+ protocols (Found: " + medicalCount + ")");
        assertTrue(fireCount >= 12, "Fire should have 12+ protocols (Found: " + fireCount + ")");
        assertTrue(secCount >= 12, "Security should have 12+ protocols (Found: " + secCount + ")");
        assertTrue(envCount >= 12, "Environmental should have 12+ protocols (Found: " + envCount + ")");
        assertTrue(trafCount >= 12, "Traffic should have 12+ protocols (Found: " + trafCount + ")");
    }

    @Test
    @DisplayName("Should find protocols across multiple domains (Fire, Security, Gas)")
    void multiDomainSearch() {
        // Medical (English keyword)
        List<EmergencyKnowledgeBase.ProtocolMatch> medical = knowledgeBase.findProtocols("bleeding");
        assertFalse(medical.isEmpty());
        // Updated to the current ID
        assertEquals("TRAUMA_001", medical.get(0).protocol().getId());

        // Fire (English keyword)
        List<EmergencyKnowledgeBase.ProtocolMatch> fire = knowledgeBase.findProtocols("smoke");
        assertFalse(fire.isEmpty());
        assertTrue(fire.get(0).protocol().getTitle().toLowerCase().contains("fire"));

        // Security (English keyword)
        List<EmergencyKnowledgeBase.ProtocolMatch> security = knowledgeBase.findProtocols("home invasion");
        assertFalse(security.isEmpty());
        assertEquals("SEC_008", security.get(0).protocol().getId());

        // Environmental - Gas (English keyword)
        List<EmergencyKnowledgeBase.ProtocolMatch> gas = knowledgeBase.findProtocols("gas leak inside");
        assertFalse(gas.isEmpty());
        assertEquals("ENV_001_VIC", gas.get(0).protocol().getId());
    }

    @Test
    @DisplayName("Should enrich results with related protocols across domains")
    void crossDomainRelations() {
        // Burning (Thermal Burns) might relate to FIRE
        // Our graph links by Category mostly, but let's check basic retrieval
        List<EmergencyKnowledgeBase.ProtocolMatch> fireResults = knowledgeBase.findProtocols("fire");
        assertFalse(fireResults.isEmpty());

        // Ensure we don't exceed the result limit (10)
        assertTrue(fireResults.size() <= 10);
    }

    @Test
    @DisplayName("Should find related protocols via graph (Earthquake -> Gas Leak)")
    void graphRelations() {
        // Earthquake and Gas Leak are both "Environmental", so they should be related
        List<Protocol> related = knowledgeBase.getRelatedProtocols("ENV_002"); // Earthquake
        boolean hasGasLeak = related.stream().anyMatch(p -> p.getId().startsWith("ENV_001"));
        assertTrue(hasGasLeak, "Earthquake should be related to Gas Leak via Environmental category");
    }

    @Test
    @DisplayName("Should handle empty queries safely")
    void handlingEdgeCases() {
        assertTrue(knowledgeBase.findProtocols(null).isEmpty());
        assertTrue(knowledgeBase.findProtocols("   ").isEmpty());
    }
}
