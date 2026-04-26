package com.soteria.infrastructure.intelligence.triage;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.Triage;
import com.soteria.infrastructure.intelligence.knowledge.EmergencyKnowledgeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DynamicTriageTest {
    private TriageService triageService;
    private EmergencyKnowledgeBase knowledgeBase;
    private final String userHome = System.getProperty("user.home");
    private final Path modelPath = Paths.get(userHome, ".soteria", "models", "CT-XLMR-SE.Q4_K_M.gguf");

    @BeforeEach
    void setUp() {
        // We use the same model for both to ensure consistency, as per ChatController
        // logic
        triageService = new TriageService(modelPath);
        knowledgeBase = new EmergencyKnowledgeBase();
        knowledgeBase.setEmbedder(triageService.getModel());
    }

    @AfterEach
    void tearDown() {
        if (triageService != null)
            triageService.close();
        if (knowledgeBase != null)
            knowledgeBase.close();
    }

    @Test
    void testDynamicTriageGasLeak() {
        String query = "I smell gas in my kitchen and I feel dizzy";

        // Step 1: RAG retrieval
        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        assertFalse(candidates.isEmpty(), "RAG should find candidates for a gas leak");

        // Step 2: Dynamic Classification
        List<Protocol> protocolList = candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();
        Triage.TriageResult result = triageService.classifyDynamic(query, protocolList);

        // Assertions
        assertTrue(result.isEmergency(), "Should be detected as emergency");
        assertEquals("ENV_001_VIC", result.protocol().getId(),
                "Should match the INDOOR gas leak protocol specifically");
        assertEquals(Triage.Intent.ENVIRONMENTAL_EMERGENCY, result.intent());
        System.out.println("Match: " + result.protocol().getTitle() + " | Score: " + result.score());
    }

    @Test
    void testDynamicTriageTornado() {
        String query = "Hay un tornado acercándose a mi pueblo, hay mucho viento";

        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        List<Protocol> protocolList = candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();
        Triage.TriageResult result = triageService.classifyDynamic(query, protocolList);

        assertTrue(result.isEmergency(), "Should detect tornado emergency even in Spanish");
        assertEquals("ENV_006", result.protocol().getId());
        System.out.println("Match: " + result.protocol().getTitle() + " | Score: " + result.score());
    }

    @Test
    void testInactiveIntent() {
        String query = "Hola, ¿cómo estás hoy? Qué buen tiempo hace.";

        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        List<Protocol> protocolList = candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();
        Triage.TriageResult result = triageService.classifyDynamic(query, protocolList);

        assertFalse(result.isEmergency(), "Casual talk should NOT be an emergency");
        assertEquals(Triage.Intent.INACTIVE, result.intent());
    }
}
