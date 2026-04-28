package com.soteria.infrastructure.intelligence.triage;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.Triage;
import com.soteria.infrastructure.intelligence.knowledge.EmergencyKnowledgeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DynamicTriageTest {
    private TriageService triageService;
    private EmergencyKnowledgeBase knowledgeBase;
    private final String userHome = System.getProperty("user.home");
    private final Path modelPath = Paths.get(userHome, ".soteria", "models", "soteria-triage-v1.gguf");

    @BeforeEach
    void setUp() {
        // We use the same model for both to ensure consistency, as per ChatController logic
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
    }

    @ParameterizedTest(name = "Triage logic test - Language: {0}")
    @CsvSource({
        "Spanish, Hay un tornado acercándose a mi pueblo hay mucho viento, ENV_006, ENVIRONMENTAL_EMERGENCY",
        "Chinese, 救命！这里着火了，我被困在二楼了。, FIRE_, ENVIRONMENTAL_EMERGENCY",
        "Arabic, أشم رائحة غاز في المطبخ وأشعر بالدوار, ENV_001_VIC, ENVIRONMENTAL_EMERGENCY",
        "Hindi, मेरी छाती में बहुत तेज़ दर्द हो रहा है और मुझे सांस लेने में तकलीफ हो रही है।, , MEDICAL_EMERGENCY",
        "French, Il y a une fuite de gaz dans mon appartement., ENV_001_VIC, ENVIRONMENTAL_EMERGENCY",
        "Japanese, キッチンでガスの臭いがして、めまいがします。, ENV_001_VIC, ENVIRONMENTAL_EMERGENCY",
        "Portuguese, Presenciei um acidente de carro e há pessoas feridas., , TRAFFIC_EMERGENCY"
    })
    void testDynamicTriageMultilingual(String lang, String query, String expectedProtocolPrefix, Triage.Intent expectedIntent) {
        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        assertFalse(candidates.isEmpty(), "RAG should find candidates for query: " + query);

        List<Protocol> protocolList = candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();
        Triage.TriageResult result = triageService.classifyDynamic(query, protocolList);

        assertTrue(result.isEmergency(), "Should detect emergency for " + lang);
        
        if (expectedProtocolPrefix != null && !expectedProtocolPrefix.isBlank()) {
            assertTrue(result.protocol().getId().startsWith(expectedProtocolPrefix), 
                "Should match " + expectedProtocolPrefix + " but got " + result.protocol().getId());
        }
        
        if (expectedIntent != null) {
            assertEquals(expectedIntent, result.intent(), "Intent mismatch for " + lang);
        }
    }

    @Test
    void testDynamicTriageRussian() {
        // "There's a strong smell of smoke and I can see fire from the window."
        String query = "Сильный запах дыма, и я вижу огонь из окна.";
        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        Triage.TriageResult result = triageService.classifyDynamic(query, candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList());
        assertTrue(result.isEmergency());
        assertTrue(result.protocol().getId().startsWith("FIRE_"));
    }

    @Test
    void testDynamicTriageBengali() {
        // "Someone is bleeding heavily from a wound."
        String query = "কারও ক্ষত থেকে প্রচুর রক্তপাত হচ্ছে।";
        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        Triage.TriageResult result = triageService.classifyDynamic(query, candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList());
        assertTrue(result.isEmergency());
        assertEquals(Triage.Intent.MEDICAL_EMERGENCY, result.intent());
    }

    @Test
    void testDynamicTriageVietnamese() {
        // "My house is flooded due to the storm."
        String query = "Nhà tôi bị ngập do bão.";
        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        Triage.TriageResult result = triageService.classifyDynamic(query, candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList());
        assertTrue(result.isEmergency());
        assertEquals(Triage.Intent.ENVIRONMENTAL_EMERGENCY, result.intent());
    }

    @Test
    void testDynamicTriageTurkish() {
        // "I'm being followed by a suspicious person."
        String query = "Şüpheli bir kişi tarafından takip ediliyorum.";
        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        Triage.TriageResult result = triageService.classifyDynamic(query, candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList());
        assertTrue(result.isEmergency());
        assertEquals(Triage.Intent.SECURITY_EMERGENCY, result.intent());
    }

    @Test
    void testInactiveIntent() {
        String query = "Hola, ¿cómo estás hoy? Qué buen tiempo hace.";

        List<KnowledgeBase.ProtocolMatch> candidates = knowledgeBase.findProtocols(query);
        List<Protocol> protocolList = candidates.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();
        Triage.TriageResult result = triageService.classifyDynamic(query, protocolList);

        assertFalse(result.isEmergency(), "Casual talk should NOT be an emergency");
        assertEquals(Triage.Intent.GREETING_OR_CASUAL, result.intent());
    }
}
