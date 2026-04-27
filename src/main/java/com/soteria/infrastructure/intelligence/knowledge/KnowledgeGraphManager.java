package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Manages the knowledge graph of protocols and their relationships.
 * Restored to original logic from EmergencyKnowledgeBase.old.java.
 */
public class KnowledgeGraphManager {
    private static final Logger logger = Logger.getLogger(KnowledgeGraphManager.class.getName());

    private final Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
    private final ProtocolRegistry registry;

    public KnowledgeGraphManager(ProtocolRegistry registry) {
        this.registry = registry;
    }

    public void buildKnowledgeGraph() {
        List<Protocol> protocols = registry.getProtocols();
        protocols.forEach(p -> graph.addVertex(p.getId()));

        for (int i = 0; i < protocols.size(); i++) {
            Protocol a = protocols.get(i);
            for (int j = i + 1; j < protocols.size(); j++) {
                Protocol b = protocols.get(j);
                if (sameCategory(a, b) || referencesEachOther(a, b)) {
                    graph.addEdge(a.getId(), b.getId());
                }
            }
        }

        logger.log(java.util.logging.Level.INFO, "Knowledge graph built with {0} vertices and {1} edges.",
                new Object[]{graph.vertexSet().size(), graph.edgeSet().size()});
    }

    private boolean sameCategory(Protocol a, Protocol b) {
        return a.getCategory() != null
                && a.getCategory().equalsIgnoreCase(b.getCategory());
    }

    private boolean referencesEachOther(Protocol a, Protocol b) {
        return stepsMention(a, b) || stepsMention(b, a);
    }

    private boolean stepsMention(Protocol source, Protocol target) {
        if (source.getSteps() == null || target.getTitle() == null) return false;
        String needle = firstTitleToken(target.getTitle());
        
        // Unicode-aware length check (CJK can be 1-2 chars, others 3+)
        boolean isCJK = needle.codePoints().anyMatch(cp -> {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                   block == Character.UnicodeBlock.HIRAGANA ||
                   block == Character.UnicodeBlock.KATAKANA ||
                   block == Character.UnicodeBlock.HANGUL_SYLLABLES;
        });

        if (needle.length() < (isCJK ? 1 : 3)) return false;

        for (String step : source.getSteps()) {
            if (step.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private String firstTitleToken(String title) {
        // Use \P{L}+ to split by any non-letter character (Unicode aware)
        String[] parts = title.toLowerCase(Locale.ROOT).split("\\P{L}+");
        for (String p : parts) {
            if (!p.isEmpty()) return p;
        }
        return "";
    }

    public List<Protocol> getRelatedProtocols(String protocolId) {
        List<Protocol> related = new ArrayList<>();
        if (!graph.containsVertex(protocolId)) return related;

        for (DefaultEdge edge : graph.edgesOf(protocolId)) {
            String source = graph.getEdgeSource(edge);
            String target = graph.getEdgeTarget(edge);
            String otherId = source.equals(protocolId) ? target : source;
            Protocol p = registry.getProtocolById(otherId);
            if (p != null) {
                related.add(p);
            }
        }
        return related;
    }

    public boolean containsEdge(String id1, String id2) {
        return graph.containsEdge(id1, id2);
    }
}
