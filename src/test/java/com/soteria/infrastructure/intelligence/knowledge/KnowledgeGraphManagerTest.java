package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeGraphManagerTest {

    private TestProtocolRegistry registry;
    private KnowledgeGraphManager graphManager;

    private static class TestProtocolRegistry extends ProtocolRegistry {
        private List<Protocol> testProtocols;
        public TestProtocolRegistry() { super("none"); }
        public void setTestProtocols(List<Protocol> protocols) { this.testProtocols = protocols; }
        @Override public List<Protocol> getProtocols() { return testProtocols; }
        @Override public Protocol getProtocolById(String id) {
            return testProtocols.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new TestProtocolRegistry();
        graphManager = new KnowledgeGraphManager(registry);
    }

    @Test
    @DisplayName("Should create edges between protocols in the same category")
    void testSameCategoryEdges() {
        Protocol p1 = new Protocol();
        p1.setId("P1");
        p1.setCategory("FIRE");
        p1.setTitle("Fire Alpha");

        Protocol p2 = new Protocol();
        p2.setId("P2");
        p2.setCategory("FIRE");
        p2.setTitle("Fire Beta");

        registry.setTestProtocols(Arrays.asList(p1, p2));
        
        graphManager.buildKnowledgeGraph();
        
        assertTrue(graphManager.containsEdge("P1", "P2"), "Should have edge between P1 and P2 (same category)");
        
        List<Protocol> related = graphManager.getRelatedProtocols("P1");
        assertTrue(related.stream().anyMatch(p -> p.getId().equals("P2")));
    }

    @Test
    @DisplayName("Should create edges when a protocol mentions another in its steps")
    void testMentionEdges() {
        Protocol p1 = new Protocol();
        p1.setId("P1");
        p1.setCategory("CAT1");
        p1.setTitle("First Aid");
        p1.setSteps(Arrays.asList("Check breathing", "If no pulse, see Cardiac protocol"));

        Protocol p2 = new Protocol();
        p2.setId("P2");
        p2.setCategory("CAT2");
        p2.setTitle("Cardiac Arrest");
        p2.setSteps(Arrays.asList("Start CPR"));

        registry.setTestProtocols(Arrays.asList(p1, p2));

        graphManager.buildKnowledgeGraph();
        
        assertTrue(graphManager.containsEdge("P1", "P2"), "Should have edge because P1 mentions P2's title token");
    }

    @Test
    @DisplayName("Should not create edges for different categories and no mentions")
    void testNoEdges() {
        Protocol p1 = new Protocol();
        p1.setId("P1");
        p1.setCategory("CAT1");
        p1.setTitle("Alpha");

        Protocol p2 = new Protocol();
        p2.setId("P2");
        p2.setCategory("CAT2");
        p2.setTitle("Beta");

        registry.setTestProtocols(Arrays.asList(p1, p2));
        
        graphManager.buildKnowledgeGraph();
        
        assertFalse(graphManager.containsEdge("P1", "P2"));
    }

    @Test
    @DisplayName("Should handle protocols with null categories or steps")
    void testNullSafe() {
        Protocol p1 = new Protocol();
        p1.setId("P1");
        p1.setCategory(null);
        p1.setSteps(null);
        p1.setTitle("Alpha");

        Protocol p2 = new Protocol();
        p2.setId("P2");
        p2.setCategory(null);
        p2.setTitle(null);

        registry.setTestProtocols(Arrays.asList(p1, p2));
        
        assertDoesNotThrow(() -> graphManager.buildKnowledgeGraph());
        assertFalse(graphManager.containsEdge("P1", "P2"));
    }

    @Test
    @DisplayName("Should create edges for multilingual mentions (CJK and Accents)")
    void testMultilingualMentions() {
        // Test case 1: CJK (Japanese) - information density allows short matches
        Protocol p1 = new Protocol();
        p1.setId("JA_FIRE");
        p1.setTitle("火災"); // Fire (Kasai)
        p1.setCategory("FIRE");

        Protocol p2 = new Protocol();
        p2.setId("JA_ADVICE");
        p2.setTitle("アドバイス"); // Advice
        p2.setSteps(Arrays.asList("火災が発生した場合は、避難してください")); // Mentioning "火災"

        // Test case 2: Accented Latin (Spanish) - should not break on 'í'
        Protocol p3 = new Protocol();
        p3.setId("ES_CARDIAC");
        p3.setTitle("Paro Cardíaco");
        p3.setCategory("MEDICAL");

        Protocol p4 = new Protocol();
        p4.setId("ES_ADVICE");
        p4.setTitle("Consejo");
        p4.setSteps(Arrays.asList("En caso de Paro Cardíaco, llame al 112"));

        registry.setTestProtocols(Arrays.asList(p1, p2, p3, p4));
        
        graphManager.buildKnowledgeGraph();
        
        assertTrue(graphManager.containsEdge("JA_FIRE", "JA_ADVICE"), 
            "Should detect Japanese mention '火災'");
        assertTrue(graphManager.containsEdge("ES_CARDIAC", "ES_ADVICE"), 
            "Should detect Spanish mention with accent 'Paro Cardíaco'");
    }
}
