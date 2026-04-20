package com.soteria.infrastructure.intelligence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MedicalKnowledgeBase Tests")
class MedicalKnowledgeBaseTest {

    private MedicalKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new MedicalKnowledgeBase();
    }

    @Test
    @DisplayName("Should load protocols from resources automatically")
    void shouldLoadProtocols() {
        List<Protocol> all = knowledgeBase.getAllProtocols();
        assertFalse(all.isEmpty(), "Protocols should be loaded from medical_protocols.json");
    }

    @Test
    @DisplayName("Should find protocol by title or keyword")
    void findProtocols() {
        // Search by title
        List<Protocol> rcpResults = knowledgeBase.findProtocols("RCP");
        assertFalse(rcpResults.isEmpty());
        assertTrue(rcpResults.get(0).getTitle().contains("RCP") || rcpResults.get(0).getTitle().contains("CPR"));

        // Search by related keyword
        List<Protocol> strokeResults = knowledgeBase.findProtocols("derrame");
        assertFalse(strokeResults.isEmpty());
        assertTrue(strokeResults.get(0).getId().equals("STK_001"));
    }

    @Test
    @DisplayName("Should enrich search results with related protocols")
    void enrichmentLogic() {
        // "obstruccion" should find Choking and possibly RCP as related
        List<Protocol> results = knowledgeBase.findProtocols("obstrucción");
        assertFalse(results.isEmpty());
        
        // Ensure we don't return too many (capped at 3 in impl)
        assertTrue(results.size() <= 3);
    }

    @Test
    @DisplayName("Should find related protocols via graph")
    void graphRelations() {
        // Stroke should be related to RCP (since steps might mention consciousness/breathing check)
        List<Protocol> related = knowledgeBase.getRelatedProtocols("STK_001");
        assertNotNull(related);
        
        // Category "Neurological" relations should be checked if any
        // But let's check basic existence first
        assertDoesNotThrow(() -> knowledgeBase.getRelatedProtocols("INVALID_ID"));
    }

    @Test
    @DisplayName("Should handle empty queries safely")
    void handlingEdgeCases() {
        assertTrue(knowledgeBase.findProtocols(null).isEmpty());
        assertTrue(knowledgeBase.findProtocols("   ").isEmpty());
    }
}
