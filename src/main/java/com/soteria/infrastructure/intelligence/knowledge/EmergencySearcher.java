package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.Term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the search logic, focusing on pure semantic retrieval
 * with knowledge graph enrichment and centroid-based centering.
 * Restored advanced scoring and enrichment logic.
 */
public class EmergencySearcher {
    private static final Logger logger = Logger.getLogger(EmergencySearcher.class.getName());

    public static final String SOURCE_SEMANTIC = "SEMANTIC";
    public static final String SOURCE_CANDIDATE = "CANDIDATE";
    public static final String SOURCE_GRAPH_BOOSTED = "GRAPH_BOOSTED";
    public static final String SOURCE_GRAPH_NEIGHBOR = "GRAPH_NEIGHBOR";
    public static final String SOURCE_KEYWORD = "KEYWORD";

    private static final int MAX_RESULTS = 30; // Restored from original
    private static final float SEMANTIC_THRESHOLD = 0.30f;
    private static final float CANDIDATE_THRESHOLD = 0.20f;

    private final LuceneIndexManager indexManager;
    private final ProtocolRegistry registry;
    private final SemanticEngine semanticEngine;
    private final KnowledgeGraphManager graphManager;

    public EmergencySearcher(LuceneIndexManager indexManager, ProtocolRegistry registry, SemanticEngine semanticEngine, KnowledgeGraphManager graphManager) {
        this.indexManager = indexManager;
        this.registry = registry;
        this.semanticEngine = semanticEngine;
        this.graphManager = graphManager;
    }

    public List<KnowledgeBase.ProtocolMatch> search(String queryText, Set<String> rejectedIds, boolean searchPrinciplesOnly) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try (IndexReader reader = DirectoryReader.open(indexManager.getIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            List<KnowledgeBase.ProtocolMatch> matches;

            if (semanticEngine.isEmbedderAvailable()) {
                matches = semanticSearch(searcher, queryText, rejectedIds, searchPrinciplesOnly);
                enrichWithRelated(matches);
            } else {
                logger.warning("Embedder unavailable. Semantic search disabled.");
                matches = new ArrayList<>();
            }
            return matches;
        } catch (Exception e) {
            logger.log(Level.SEVERE, e, () -> "Search failed for query: " + queryText);
            return new ArrayList<>();
        }
    }

    private List<KnowledgeBase.ProtocolMatch> semanticSearch(IndexSearcher searcher, String queryText, Set<String> rejectedIds, boolean searchPrinciplesOnly) throws IOException {
        List<KnowledgeBase.ProtocolMatch> results = new ArrayList<>();

        logger.log(Level.INFO, () -> String.format("========== [RAG-QUERY] '%s' ==========", queryText));

        long tEmbed = System.nanoTime();
        float[] queryVector = semanticEngine.embedQuery(queryText);
        long embedMs = (System.nanoTime() - tEmbed) / 1_000_000L;

        if (queryVector == null || queryVector.length == 0) return results;

        logger.log(Level.INFO, () -> String.format(
                "[RAG-QUERY] dims=%d embed_ms=%d centered=%b",
                queryVector.length, embedMs, (semanticEngine.getCentroid() != null)));

        BooleanQuery query = buildSearchQuery(queryVector, rejectedIds, searchPrinciplesOnly);

        long tSearch = System.nanoTime();
        TopDocs docs = searcher.search(query, MAX_RESULTS);
        long searchMs = (System.nanoTime() - tSearch) / 1_000_000L;
        logger.log(Level.INFO, "[RAG-QUERY] knn_search_ms={0} hits={1}",
                new Object[]{searchMs, docs.scoreDocs.length});

        List<float[]> scoredRows = computeScores(searcher, docs.scoreDocs);
        
        scoredRows.sort((a, b) -> Float.compare(b[1], a[1])); // Sort by semantic score

        List<KnowledgeBase.ProtocolMatch> processed = processRerankedHits(searcher, scoredRows, results);

        logger.log(Level.INFO, "[RAG-QUERY] kept_semantic={0}",
                new Object[]{
                        processed.stream().filter(m -> SOURCE_SEMANTIC.equals(m.source())).count()
                });

        return processed;
    }

    private BooleanQuery buildSearchQuery(float[] queryVector, Set<String> rejectedIds, boolean searchPrinciplesOnly) {
        KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(LuceneIndexManager.FIELD_VECTOR, queryVector, MAX_RESULTS);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(knnQuery, BooleanClause.Occur.MUST);
        if (!rejectedIds.isEmpty()) {
            for (String id : rejectedIds) {
                builder.add(new TermQuery(new Term(LuceneIndexManager.FIELD_ID, id)), BooleanClause.Occur.MUST_NOT);
            }
        }

        TermQuery principleFilter = new TermQuery(new Term(LuceneIndexManager.FIELD_CATEGORY, "Principle"));
        if (searchPrinciplesOnly) {
            builder.add(principleFilter, BooleanClause.Occur.FILTER);
        } else {
            builder.add(principleFilter, BooleanClause.Occur.MUST_NOT);
        }
        return builder.build();
    }

    private List<float[]> computeScores(IndexSearcher searcher, ScoreDoc[] scoreDocs) throws IOException {
        List<float[]> scored = new ArrayList<>();
        int rank = 0;
        for (ScoreDoc sd : scoreDocs) {
            rank++;
            Document doc = searcher.storedFields().document(sd.doc);
            String id = doc.get(LuceneIndexManager.FIELD_ID);

            Protocol p = registry.getProtocolById(id);
            if (p != null) {
                float pureSemantic = Math.max(0.0f, (sd.score * 2.0f) - 1.0f);
                
                final int r = rank;
                logger.log(Level.INFO, () -> String.format(
                        "[RAG-HIT-RAW] rank=%d lucene=%.4f pure=%.4f id=%s title='%s'",
                        r, sd.score, pureSemantic, id, doc.get(LuceneIndexManager.FIELD_TITLE)));
                
                scored.add(new float[]{sd.doc, pureSemantic});
            }
        }
        return scored;
    }

    private List<KnowledgeBase.ProtocolMatch> processRerankedHits(IndexSearcher searcher, List<float[]> scoredRows, List<KnowledgeBase.ProtocolMatch> results) throws IOException {
        int finalRank = 0;
        for (float[] row : scoredRows) {
            finalRank++;
            Document doc = searcher.storedFields().document((int) row[0]);
            String id = doc.get(LuceneIndexManager.FIELD_ID);
            Protocol p = registry.getProtocolById(id);
            if (p == null) continue;

            float score = row[1];
            String classification = classifyScore(score);
            String status = classification != null ? classification : "DROPPED";

            final int fr = finalRank;
            logger.log(Level.INFO, () -> String.format(
                    "[RAG-HIT] rank=%d score=%.4f status=%s id=%s title='%s'",
                    fr, score, status, id, doc.get(LuceneIndexManager.FIELD_TITLE)));

            if (classification != null) {
                results.add(new KnowledgeBase.ProtocolMatch(p, classification, score));
            }
        }
        return results;
    }

    private String classifyScore(float score) {
        if (score >= SEMANTIC_THRESHOLD) return SOURCE_SEMANTIC;
        if (score >= CANDIDATE_THRESHOLD) return SOURCE_CANDIDATE;
        return null;
    }

    private void enrichWithRelated(List<KnowledgeBase.ProtocolMatch> matches) {
        if (matches.isEmpty()) return;

        // 1. Extract high-confidence hits as anchors
        List<KnowledgeBase.ProtocolMatch> anchors = matches.stream()
                .filter(m -> SOURCE_SEMANTIC.equals(m.source()))
                .toList();

        if (anchors.isEmpty()) {
            return;
        }

        // 2. Promotion: Neighbors of anchors are boosted
        for (int i = 0; i < matches.size(); i++) {
            KnowledgeBase.ProtocolMatch match = matches.get(i);
            if (SOURCE_CANDIDATE.equals(match.source())) {
                boolean isNeighbor = anchors.stream()
                        .anyMatch(a -> graphManager.containsEdge(a.protocol().getId(), match.protocol().getId()));

                if (isNeighbor) {
                    logger.info("Graph: Promoting '" + match.protocol().getTitle() + "' to GRAPH_BOOSTED");
                    matches.set(i, new KnowledgeBase.ProtocolMatch(match.protocol(), SOURCE_GRAPH_BOOSTED, match.score()));
                }
            }
        }

        // Clean up remaining unloved candidates
        matches.removeIf(m -> SOURCE_CANDIDATE.equals(m.source()));

        // 3. Expansion: Add direct neighbors of the TOP anchor
        Protocol topAnchor = anchors.get(0).protocol();
        List<Protocol> related = graphManager.getRelatedProtocols(topAnchor.getId());
        
        for (Protocol p : related) {
            if (matches.size() >= MAX_RESULTS) break;
            boolean alreadyPresent = matches.stream().anyMatch(m -> m.protocol().getId().equals(p.getId()));
            if (!alreadyPresent) {
                logger.info("Graph: Adding neighbor '" + p.getTitle() + "' (Relation to " + topAnchor.getTitle() + ")");
                matches.add(new KnowledgeBase.ProtocolMatch(p, SOURCE_GRAPH_NEIGHBOR, 0.01f));
            }
        }
    }

    /**
     * Diagnostic tool: Prints top 5 raw hits from Lucene for a query.
     * Replicated logic from EmergencyKnowledgeBase.old.java for debugging.
     */
    public void rawDiagnosticSearch(String queryText) {
        try (IndexReader reader = DirectoryReader.open(indexManager.getIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            if (!semanticEngine.isEmbedderAvailable()) {
                logger.info("  Embedder not initialized.");
                return;
            }
            float[] queryVector = semanticEngine.embedQuery(queryText);
            Query knnQuery = new KnnFloatVectorQuery(LuceneIndexManager.FIELD_VECTOR, queryVector, 5);
            TopDocs docs = searcher.search(knnQuery, 5);

            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = reader.storedFields().document(sd.doc);
                String id = doc.get(LuceneIndexManager.FIELD_ID);
                String title = doc.get(LuceneIndexManager.FIELD_TITLE);

                float pureSemantic = Math.max(0.0f, (sd.score * 2.0f) - 1.0f);
                float combined = pureSemantic;

                logger.info(() -> String.format("  ID: %s | Title: %-25s | Total: %.4f [Sem: %.4f]",
                        id, title, combined, pureSemantic));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Diagnostic search failed", e);
        }
    }
}
