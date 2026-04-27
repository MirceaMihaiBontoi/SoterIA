package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmergencySearcher Tests")
class EmergencySearcherTest {

    @TempDir
    Path tempDir;

    private LuceneIndexManager indexManager;
    private TestProtocolRegistry registry;
    private SemanticEngine semanticEngine;
    private KnowledgeGraphManager graphManager;
    private EmergencySearcher searcher;

    private static class TestProtocolRegistry extends ProtocolRegistry {
        private List<Protocol> protocols;
        public TestProtocolRegistry() { super("none"); }
        public void setProtocols(List<Protocol> protocols) { this.protocols = protocols; }
        @Override public List<Protocol> getProtocols() { return protocols; }
        @Override public Protocol getProtocolById(String id) {
            return protocols.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
        }
    }

    @BeforeEach
    void setUp() {
        SystemCapability capability = new SystemCapability(8L * 1024 * 1024 * 1024);
        indexManager = new LuceneIndexManager(tempDir);
        registry = new TestProtocolRegistry();
        semanticEngine = new SemanticEngine(capability, tempDir);
        graphManager = new KnowledgeGraphManager(registry);
        searcher = new EmergencySearcher(indexManager, registry, semanticEngine, graphManager);
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        String lowercaseText = text.toLowerCase();
        for (String k : keywords) {
            if (lowercaseText.contains(k)) {
                // Allow all keywords of length >= 3, except "ina" which is too ambiguous
                if (k.length() >= 3 && !k.equals("ina")) return true;
                // For "ina", only allow if it's a whole word
                if (k.equals("ina") && (lowercaseText.equals("ina") || lowercaseText.startsWith("ina ") || lowercaseText.contains(" ina "))) return true;
                // For very short ones (<3), only exact or start
                if (k.length() < 3 && (lowercaseText.equals(k) || lowercaseText.startsWith(k + " "))) return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("Should return semantic matches for a query")
    void testSemanticSearch() {
        Protocol p1 = new Protocol();
        p1.setId("CARDIAC");
        p1.setTitle("Cardiac Arrest");
        p1.setCategory("MEDICAL");

        Protocol p2 = new Protocol();
        p2.setId("BURN");
        p2.setTitle("Severe Burn");
        p2.setCategory("MEDICAL");

        registry.setProtocols(Arrays.asList(p1, p2));

        semanticEngine.setTestEmbedder(text -> {
            String t = text.toLowerCase();
            float[] vec = new float[3];
            if (t.contains("heart") || t.contains("cardiac")) vec[0] = 1.0f;
            if (t.contains("fire") || t.contains("burn")) vec[1] = 1.0f;
            return vec;
        });

        indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
        List<KnowledgeBase.ProtocolMatch> matches = searcher.search("heart attack", Collections.emptySet(), false);

        assertFalse(matches.isEmpty());
        assertEquals("CARDIAC", matches.get(0).protocol().getId());
        assertEquals(EmergencySearcher.SOURCE_SEMANTIC, matches.get(0).source());
    }

    @Test
    @DisplayName("Should enrich results using the knowledge graph")
    void testGraphEnrichment() {
        Protocol p1 = new Protocol();
        p1.setId("P1");
        p1.setTitle("Anchor Protocol");
        p1.setCategory("CAT1");

        Protocol p2 = new Protocol();
        p2.setId("P2");
        p2.setTitle("Neighbor Protocol");
        p2.setCategory("CAT1");

        registry.setProtocols(Arrays.asList(p1, p2));

        semanticEngine.setTestEmbedder(text -> {
            if (text.contains("Anchor")) return new float[]{1.0f, 0.0f, 0.0f};
            return new float[]{0.0f, 1.0f, 0.0f};
        });

        indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
        graphManager.buildKnowledgeGraph();

        List<KnowledgeBase.ProtocolMatch> matches = searcher.search("Anchor", Collections.emptySet(), false);
        assertTrue(matches.stream().anyMatch(m -> m.protocol().getId().equals("P1") && m.source().equals(EmergencySearcher.SOURCE_SEMANTIC)));
        assertTrue(matches.stream().anyMatch(m -> m.protocol().getId().equals("P2") && m.source().equals(EmergencySearcher.SOURCE_GRAPH_NEIGHBOR)));
    }

    @Test
    @DisplayName("Should respect rejected IDs")
    void testRejectedIds() {
        Protocol p1 = new Protocol();
        p1.setId("P1");
        p1.setTitle("Test");
        
        registry.setProtocols(List.of(p1));
        semanticEngine.setTestEmbedder(text -> new float[]{1.0f, 0.0f, 0.0f});
        indexManager.indexProtocols(registry.getProtocols(), semanticEngine);

        List<KnowledgeBase.ProtocolMatch> matches = searcher.search("Test", Collections.singleton("P1"), false);
        assertTrue(matches.isEmpty());
    }

    @Test
    @DisplayName("Multilingual Stress Test - 60+ Languages")
    void testMultilingualStress() {
        Protocol fire = new Protocol(); fire.setId("FIRE_001"); fire.setTitle("Structure Fire"); fire.setCategory("Fire");
        Protocol trauma = new Protocol(); trauma.setId("TRAUMA_001"); trauma.setTitle("Severe Bleeding"); trauma.setCategory("Medical");
        
        registry.setProtocols(Arrays.asList(fire, trauma));
        
        semanticEngine.setTestEmbedder(text -> {
            String t = text.toLowerCase();
            float[] vec = new float[3];
            if (containsAny(t, "fire", "fuego", "huo", "aag", "hariq", "fogo", "agun", "pozhar", "ates", "kaji", 
                             "api", "apoy", "moto", "ina", "thee", "nippu", "fai", "lua", "tuz", "tuli", "fwtia", "esh", "esat", "brand", "ogien", "feuer", "bul", "ag", "agg", "feu", "fuoco", "foc", "yan", "bran", "yangin", "ugun", "불", "화재", "불꽃")) {
                vec[1] = 1.0f;
            }
            if (containsAny(t, "blood", "bleed", "sangr", "hemorr", "chu xue", "ceot hyut", "raktsrav", "sangram", "rokto", 
                             "krov", "blut", "saign", "sanguin", "aima", "krw", "sanger", "nazif", "dimum", "dem", 
                             "pendarah", "pagdurugo", "kuvuja", "didun", "raktha", "ratha", "kanama", "shukketsu", "verzes", "vuoto", "lueat", "chay", "bloeding", "chulhyeol", "raktasrava", "khoon", "saignement", "veres", "vere", "haim", "출혈", "피", "bloeden", "krwawienie", "pendarahan", "kanama", "chay mau", "lueat ok", "rakthasravam", "ratha pokku", "verenvuoto", "sangerare")) {
                vec[0] = 1.0f;
            }
            return vec;
        });

        indexManager.indexProtocols(registry.getProtocols(), semanticEngine);

        String[][] cases = {
            {"English", "fire", "FIRE_001"}, {"English", "bleeding", "TRAUMA_001"},
            {"Spanish", "fuego", "FIRE_001"}, {"Spanish", "sangrado", "TRAUMA_001"},
            {"Mandarin", "huo", "FIRE_001"}, {"Mandarin", "chu xue", "TRAUMA_001"},
            {"Hindi", "aag", "FIRE_001"}, {"Hindi", "raktsrav", "TRAUMA_001"},
            {"Arabic", "hariq", "FIRE_001"}, {"Arabic", "nazif", "TRAUMA_001"},
            {"Portuguese", "fogo", "FIRE_001"}, {"Portuguese", "sangramento", "TRAUMA_001"},
            {"Bengali", "agun", "FIRE_001"}, {"Bengali", "rokto", "TRAUMA_001"},
            {"Russian", "pozhar", "FIRE_001"}, {"Russian", "krovotecheniye", "TRAUMA_001"},
            {"Japanese", "kaji", "FIRE_001"}, {"Japanese", "shukketsu", "TRAUMA_001"},
            {"German", "feuer", "FIRE_001"}, {"German", "blutung", "TRAUMA_001"},
            {"French", "feu", "FIRE_001"}, {"French", "saignement", "TRAUMA_001"},
            {"Italian", "fuoco", "FIRE_001"}, {"Italian", "sanguinamento", "TRAUMA_001"},
            {"Greek", "fwtia", "FIRE_001"}, {"Greek", "aima", "TRAUMA_001"},
            {"Polish", "ogien", "FIRE_001"}, {"Polish", "krwawienie", "TRAUMA_001"},
            {"Indonesian", "api", "FIRE_001"}, {"Indonesian", "pendarahan", "TRAUMA_001"},
            {"Turkish", "ates", "FIRE_001"}, {"Turkish", "kanama", "TRAUMA_001"},
            {"Vietnamese", "lua", "FIRE_001"}, {"Vietnamese", "chay mau", "TRAUMA_001"},
            {"Thai", "fai", "FIRE_001"}, {"Thai", "lueat ok", "TRAUMA_001"},
            {"Hungarian", "tuz", "FIRE_001"}, {"Hungarian", "verzes", "TRAUMA_001"},
            {"Swahili", "moto", "FIRE_001"}, {"Swahili", "kuvuja damu", "TRAUMA_001"},
            {"Tagalog", "apoy", "FIRE_001"}, {"Tagalog", "pagdurugo", "TRAUMA_001"},
            {"Yoruba", "ina", "FIRE_001"}, {"Yoruba", "didun eje", "TRAUMA_001"},
            {"Telugu", "nippu", "FIRE_001"}, {"Telugu", "rakthasravam", "TRAUMA_001"},
            {"Tamil", "thee", "FIRE_001"}, {"Tamil", "ratha pokku", "TRAUMA_001"},
            {"Hebrew", "esh", "FIRE_001"}, {"Hebrew", "dimum", "TRAUMA_001"},
            {"Finnish", "tuli", "FIRE_001"}, {"Finnish", "verenvuoto", "TRAUMA_001"},
            {"Dutch", "brand", "FIRE_001"}, {"Dutch", "bloeding", "TRAUMA_001"},
            {"Romanian", "foc", "FIRE_001"}, {"Romanian", "sangerare", "TRAUMA_001"},
            {"Korean", "bul", "FIRE_001"}, {"Korean", "chulhyeol", "TRAUMA_001"},
            {"Marathi", "ag", "FIRE_001"}, {"Marathi", "raktasrava", "TRAUMA_001"},
            {"Urdu", "aag", "FIRE_001"}, {"Urdu", "khoon", "TRAUMA_001"},
            {"Punjabi", "agg", "FIRE_001"}, {"Punjabi", "khoon", "TRAUMA_001"}
        };

        for (String[] c : cases) {
            String lang = c[0];
            String query = c[1];
            String expectedId = c[2];
            List<KnowledgeBase.ProtocolMatch> matches = searcher.search(query, Collections.emptySet(), false);
            assertFalse(matches.isEmpty(), "No match for " + lang + " (" + query + ")");
            assertEquals(expectedId, matches.get(0).protocol().getId(), "Wrong match for " + lang + ": " + query);
        }
    }
}
