package com.soteria.infrastructure.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native Medical Knowledge Base using Apache Lucene (retrieval) and
 * JGraphT (related-protocol discovery).
 */
public class MedicalKnowledgeBase {
    private static final Logger logger = Logger.getLogger(MedicalKnowledgeBase.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 3;

    private List<Protocol> protocols;
    private Map<String, Protocol> protocolsById;
    private Directory index;
    private StandardAnalyzer analyzer;
    private Graph<String, DefaultEdge> knowledgeGraph;

    public MedicalKnowledgeBase() {
        this.analyzer = new StandardAnalyzer();
        this.index = new ByteBuffersDirectory();
        this.knowledgeGraph = new SimpleGraph<>(DefaultEdge.class);
        loadProtocols();
        indexProtocols();
        buildKnowledgeGraph();
    }

    private void loadProtocols() {
        try (InputStream is = getClass().getResourceAsStream("/data/medical_protocols.json")) {
            if (is == null) {
                logger.severe("CRITICAL: medical_protocols.json not found in resources.");
                this.protocols = new ArrayList<>();
                this.protocolsById = new LinkedHashMap<>();
                return;
            }
            this.protocols = mapper.readValue(is, new TypeReference<List<Protocol>>() {});
            this.protocolsById = new LinkedHashMap<>();
            for (Protocol p : protocols) {
                protocolsById.put(p.getId(), p);
            }
            logger.info("Loaded " + protocols.size() + " medical protocols.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to parse medical protocols JSON", e);
            this.protocols = new ArrayList<>();
            this.protocolsById = new LinkedHashMap<>();
        }
    }

    private void indexProtocols() {
        try (IndexWriter writer = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
            for (Protocol p : protocols) {
                Document doc = new Document();
                doc.add(new StringField("id", p.getId(), Field.Store.YES));
                doc.add(new TextField("title", p.getTitle(), Field.Store.YES));
                String keywordString = String.join(" ", p.getKeywords());
                doc.add(new TextField("keywords", keywordString, Field.Store.YES));
                writer.addDocument(doc);
            }
            logger.info("Indexed all protocols in-memory.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lucene indexing failed", e);
        }
    }

    /**
     * Builds an undirected relationship graph. Two protocols are linked when:
     *   (a) they share the same category, or
     *   (b) one protocol's steps explicitly reference another by title
     *       (e.g. choking → CPR via "start CPR immediately").
     */
    private void buildKnowledgeGraph() {
        for (Protocol p : protocols) {
            knowledgeGraph.addVertex(p.getId());
        }

        for (int i = 0; i < protocols.size(); i++) {
            Protocol a = protocols.get(i);
            for (int j = i + 1; j < protocols.size(); j++) {
                Protocol b = protocols.get(j);
                if (sameCategory(a, b) || referencesEachOther(a, b)) {
                    knowledgeGraph.addEdge(a.getId(), b.getId());
                }
            }
        }
        logger.info(() -> "Knowledge graph built: "
                + knowledgeGraph.vertexSet().size() + " nodes, "
                + knowledgeGraph.edgeSet().size() + " edges.");
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
        if (needle.length() < 3) return false;
        for (String step : source.getSteps()) {
            if (step.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private String firstTitleToken(String title) {
        String[] parts = title.toLowerCase(Locale.ROOT).split("[^a-z]+");
        for (String p : parts) {
            if (p.length() >= 3) return p;
        }
        return "";
    }

    /**
     * Searches for protocols matching the user query, then enriches the
     * result with graph neighbors of the top hit (deduped, capped).
     */
    public List<Protocol> findProtocols(String queryText) {
        List<Protocol> results = new ArrayList<>();
        if (queryText == null || queryText.trim().isEmpty()) return results;

        try (IndexReader reader = DirectoryReader.open(index)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            StoredFields stored = searcher.storedFields();

            String[] fields = {"title", "keywords"};
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);
            Query query = parser.parse(queryText + "*");

            TopDocs docs = searcher.search(query, MAX_RESULTS);
            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = stored.document(sd.doc);
                Protocol p = protocolsById.get(doc.get("id"));
                if (p != null && !results.contains(p)) {
                    results.add(p);
                }
            }

            enrichWithRelated(results);
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> "Retrieval failed for query: " + queryText);
        }
        return results;
    }

    private void enrichWithRelated(List<Protocol> results) {
        if (results.isEmpty()) return;
        String topHitId = results.get(0).getId();
        for (Protocol related : getRelatedProtocols(topHitId)) {
            if (results.size() >= MAX_RESULTS) break;
            if (!results.contains(related)) {
                results.add(related);
            }
        }
    }

    /**
     * Returns protocols directly linked to the given one in the knowledge graph.
     */
    public List<Protocol> getRelatedProtocols(String protocolId) {
        List<Protocol> related = new ArrayList<>();
        if (protocolId == null || !knowledgeGraph.containsVertex(protocolId)) return related;
        for (String neighborId : Graphs.neighborListOf(knowledgeGraph, protocolId)) {
            Protocol p = protocolsById.get(neighborId);
            if (p != null) related.add(p);
        }
        return related;
    }

    public List<Protocol> getAllProtocols() {
        return new ArrayList<>(protocols);
    }
}
