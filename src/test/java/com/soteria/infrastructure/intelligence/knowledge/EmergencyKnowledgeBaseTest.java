package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmergencyKnowledgeBase Tests")
class EmergencyKnowledgeBaseTest {

    private EmergencyKnowledgeBase knowledgeBase;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        SystemCapability capability = new SystemCapability(16L * 1024 * 1024 * 1024);
        knowledgeBase = new EmergencyKnowledgeBase("/data/protocols/", tempDir, capability);
        
        knowledgeBase.setTestEmbedder(text -> {
            String lower = text.toLowerCase();
            String norm = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                    .replaceAll("(?<=\\p{IsLatin})\\p{M}+", "");
            float[] vec = new float[10];
            
            // 1. ID-based classification for stable indexing (Priority)
            if (text.contains("MED_") || text.contains("VITAL_")) vec[0] = 1.0f;
            else if (text.contains("FIRE_")) vec[1] = 1.0f;
            else if (text.contains("TRAUMA_")) vec[2] = 1.0f;
            else if (text.contains("SEC_")) vec[3] = 1.0f;
            else if (text.contains("TRAF_")) vec[4] = 1.0f;
            else if (text.contains("ENV_")) vec[5] = 1.0f;
            
            // 2. Keyword-based classification for queries (Independent matches)
            boolean kwMatch = false;
            // Dimension 0: Medical / Vital
            if (lower.contains("\uc228\uc744") || lower.contains("\u547c\u5438") || 
                lower.contains("\u062a\u0646\u0641\u0633") || norm.contains("breath") || 
                norm.contains("respir") || norm.contains("tho") || norm.contains("iki") || 
                norm.contains("anapneei") || norm.contains("dyshat") || 
                norm.contains("\u0434\u044b\u0448") || norm.contains("nefes") ||
                norm.contains("tinh") || norm.contains("unconscious") || norm.contains("cpr") || 
                norm.contains("heart") || norm.contains("cardiac")) {
                vec[0] = 1.0f; kwMatch = true;
            }
            // Dimension 1: Fire
            if (lower.contains("\u7740\u706b") || lower.contains("\u062d\u0631\u064a\u0642") ||
                lower.contains("fai mai") || lower.contains("esh") ||
                norm.contains("fire") || norm.contains("fuego") || norm.contains("incend") || 
                norm.contains("smoke") || norm.contains("humo") || norm.contains("brennt") || 
                norm.contains("kaji") || norm.contains("moto") || norm.contains("pozar") || 
                norm.contains("tuz") || norm.contains("lua") || norm.contains("\ubd88") ||
                norm.contains("fwtia") || norm.contains("yangin") ||
                norm.contains("kebakaran") || norm.contains("ogon") || norm.contains("brand")) {
                vec[1] = 1.0f; kwMatch = true;
            }
            // Dimension 2: Trauma / Bleeding
            if (lower.contains("\u0916\u0942\u0928") || lower.contains("\u00ec\u0074\u00e0\u006a\u1eb9\u0300") ||
                lower.contains("\u6d41\u8840") || lower.contains("\u0646\u0632\u064a\u0641") ||
                norm.contains("sangr") || norm.contains("hemorr") || norm.contains("blood") || 
                norm.contains("bleeding") || norm.contains("blut") || norm.contains("krv") || 
                norm.contains("krw") || norm.contains("damu") || norm.contains("mau") || 
                norm.contains("rokto") || norm.contains("rakth") || norm.contains("wound") || 
                norm.contains("herida") || norm.contains("vet thuong") || norm.contains("gay") ||
                norm.contains("kanama") || norm.contains("pendarahan") || norm.contains("fracture")) {
                vec[2] = 1.0f; kwMatch = true;
            }
            if (norm.contains("ataque") || norm.contains("seguridad") || norm.contains("rob") || 
                norm.contains("threat") || norm.contains("danger")) {
                vec[3] = 1.0f; kwMatch = true;
            }
            if (norm.contains("accident") || norm.contains("car") || norm.contains("traf") || 
                norm.contains("coche") || norm.contains("xe")) {
                vec[4] = 1.0f; kwMatch = true;
            }
            if (norm.contains("clima") || norm.contains("weather") || norm.contains("env") || 
                norm.contains("flood") || norm.contains("storm")) {
                vec[5] = 1.0f; kwMatch = true;
            }
            
            if (!kwMatch && vec[0] == 0 && vec[1] == 0 && vec[2] == 0 && vec[3] == 0 && vec[4] == 0 && vec[5] == 0) {
                vec[9] = 1.0f;
            }
            
            return VectorMath.normalize(vec);
        });
    }

    @Test
    @DisplayName("Load All Protocols from Manifest")
    void shouldLoadProtocols() {
        List<Protocol> all = knowledgeBase.getAllProtocols();
        assertTrue(all.size() >= 60, "Should load protocols from manifest");
    }

    @Test
    @DisplayName("Comprehensive Multilingual Semantic Search")
    void multilingualSearch() {
        assertAll("Multilingual search matches",
            () -> assertSearchMatch("severe bleeding", "TRAUMA_001"),
            () -> assertSearchMatch("hay un incendio", "FIRE_001_VIC"),

            () -> assertSearchMatch("no respira", "VITAL_001")
        );
    }

    @Test
    @DisplayName("Multi-Domain Ambiguity Handling")
    void multiDomainSearch() {
        List<KnowledgeBase.ProtocolMatch> matches = knowledgeBase.findProtocols("fuego y heridos con sangrado", Collections.emptySet(), false);
        assertTrue(matches.size() >= 2, "Should find multiple protocols");
        
        boolean hasFire = matches.stream().anyMatch(m -> m.protocol().getId().startsWith("FIRE"));
        boolean hasMedical = matches.stream().anyMatch(m -> m.protocol().getId().startsWith("TRAUMA") || m.protocol().getCategory().equalsIgnoreCase("Medical") || m.protocol().getCategory().equalsIgnoreCase("Trauma"));
        
        assertTrue(hasFire, "Should match Fire");
        assertTrue(hasMedical, "Should match Medical/Trauma");
    }

    private void assertSearchMatch(String query, String expectedId) {
        List<KnowledgeBase.ProtocolMatch> results = knowledgeBase.findProtocols(query, Collections.emptySet(), false);
        if (results.isEmpty()) {
            fail("No results found for query: " + query);
        }
        
        boolean found = results.stream().anyMatch(m -> m.protocol().getId().equalsIgnoreCase(expectedId));
        
        if (!found) {
            String foundIds = results.stream()
                .map(m -> m.protocol().getId() + " [" + m.score() + "]")
                .collect(Collectors.joining(", "));
            fail("Query '" + query + "' expected " + expectedId + " but found: " + foundIds);
        }
    }

    @AfterEach
    void tearDown() {
        if (knowledgeBase != null) knowledgeBase.close();
    }
}
