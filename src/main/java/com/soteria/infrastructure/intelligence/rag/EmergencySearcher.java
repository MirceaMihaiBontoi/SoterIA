package com.soteria.infrastructure.intelligence.rag;

import com.soteria.core.domain.emergency.Protocol;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
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
    private static final float LEXICAL_WEIGHT = 0.2f;

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

    public List<EmergencyKnowledgeBase.ProtocolMatch> search(String queryText, Set<String> rejectedIds, boolean searchPrinciplesOnly) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try (IndexReader reader = DirectoryReader.open(indexManager.getIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            List<EmergencyKnowledgeBase.ProtocolMatch> matches;

            if (semanticEngine.getEmbedder() != null) {
                matches = semanticSearch(searcher, queryText, rejectedIds, searchPrinciplesOnly);
                enrichWithRelated(matches);
            } else {
                logger.warning("Embedder unavailable. Using Keyword search safety net.");
                List<Protocol> results = keywordSearch(searcher, queryText, searchPrinciplesOnly);
                matches = new ArrayList<>();
                for (Protocol p : results) {
                    if (!rejectedIds.contains(p.getId())) {
                        matches.add(new EmergencyKnowledgeBase.ProtocolMatch(p, SOURCE_KEYWORD, 1.0f));
                    }
                }
            }
            return matches;
        } catch (Exception e) {
            logger.log(Level.SEVERE, e, () -> "Search failed for query: " + queryText);
            return new ArrayList<>();
        }
    }

    private List<EmergencyKnowledgeBase.ProtocolMatch> semanticSearch(IndexSearcher searcher, String queryText, Set<String> rejectedIds, boolean searchPrinciplesOnly) throws IOException {
        List<EmergencyKnowledgeBase.ProtocolMatch> results = new ArrayList<>();

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

        Set<String> queryTokens = VectorMath.tokenize(queryText);
        List<float[]> scoredRows = computeScores(searcher, docs.scoreDocs, queryTokens);
        
        scoredRows.sort((a, b) -> Float.compare(b[3], a[3]));

        List<EmergencyKnowledgeBase.ProtocolMatch> processed = processRerankedHits(searcher, scoredRows, results);

        logger.log(Level.INFO, "[RAG-QUERY] kept_semantic={0} kept_candidate={1}",
                new Object[]{
                        processed.stream().filter(m -> SOURCE_SEMANTIC.equals(m.source())).count(),
                        processed.stream().filter(m -> SOURCE_CANDIDATE.equals(m.source())).count()
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

    private List<float[]> computeScores(IndexSearcher searcher, ScoreDoc[] scoreDocs, Set<String> queryTokens) throws IOException {
        List<float[]> scored = new ArrayList<>();
        int rank = 0;
        for (ScoreDoc sd : scoreDocs) {
            rank++;
            Document doc = searcher.storedFields().document(sd.doc);
            String id = doc.get(LuceneIndexManager.FIELD_ID);

            Protocol p = registry.getProtocolById(id);
            if (p != null) {
                float pureSemantic = Math.max(0.0f, (sd.score * 2.0f) - 1.0f);
                float lexical = VectorMath.lexicalOverlap(queryTokens, p);
                float combined = pureSemantic + LEXICAL_WEIGHT * lexical;
                
                final int r = rank;
                logger.log(Level.INFO, () -> String.format(
                        "[RAG-HIT-RAW] rank=%d lucene=%.4f pure=%.4f lex=%.4f combined=%.4f id=%s title='%s'",
                        r, sd.score, pureSemantic, lexical, combined, id, doc.get(LuceneIndexManager.FIELD_TITLE)));
                
                scored.add(new float[]{sd.doc, pureSemantic, lexical, combined});
            }
        }
        return scored;
    }

    private List<EmergencyKnowledgeBase.ProtocolMatch> processRerankedHits(IndexSearcher searcher, List<float[]> scoredRows, List<EmergencyKnowledgeBase.ProtocolMatch> results) throws IOException {
        int finalRank = 0;
        for (float[] row : scoredRows) {
            finalRank++;
            Document doc = searcher.storedFields().document((int) row[0]);
            String id = doc.get(LuceneIndexManager.FIELD_ID);
            Protocol p = registry.getProtocolById(id);
            if (p == null) continue;

            float combined = row[3];
            String classification = classifyScore(combined);
            String status = classification != null ? classification : "DROPPED";

            final int fr = finalRank;
            logger.log(Level.INFO, () -> String.format(
                    "[RAG-HIT] rerank=%d combined=%.4f cos=%.4f lex=%.4f status=%s id=%s title='%s'",
                    fr, combined, row[1], row[2], status, id, doc.get(LuceneIndexManager.FIELD_TITLE)));

            if (classification != null) {
                results.add(new EmergencyKnowledgeBase.ProtocolMatch(p, classification, combined));
            }
        }
        return results;
    }

    private String classifyScore(float score) {
        if (score >= SEMANTIC_THRESHOLD) return SOURCE_SEMANTIC;
        if (score >= CANDIDATE_THRESHOLD) return SOURCE_CANDIDATE;
        return null;
    }

    private void enrichWithRelated(List<EmergencyKnowledgeBase.ProtocolMatch> matches) {
        if (matches.isEmpty()) return;

        // 1. Extract high-confidence hits as anchors
        List<EmergencyKnowledgeBase.ProtocolMatch> anchors = matches.stream()
                .filter(m -> SOURCE_SEMANTIC.equals(m.source()))
                .toList();

        if (anchors.isEmpty()) {
            // If no primary hits, we keep candidates as they might be relevant enough
            return;
        }

        // 2. Promotion: Neighbors of anchors are boosted
        for (int i = 0; i < matches.size(); i++) {
            EmergencyKnowledgeBase.ProtocolMatch match = matches.get(i);
            if (SOURCE_CANDIDATE.equals(match.source())) {
                boolean isNeighbor = anchors.stream()
                        .anyMatch(a -> graphManager.containsEdge(a.protocol().getId(), match.protocol().getId()));

                if (isNeighbor) {
                    logger.info("Graph: Promoting '" + match.protocol().getTitle() + "' to GRAPH_BOOSTED");
                    matches.set(i, new EmergencyKnowledgeBase.ProtocolMatch(match.protocol(), SOURCE_GRAPH_BOOSTED, match.score()));
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
                matches.add(new EmergencyKnowledgeBase.ProtocolMatch(p, SOURCE_GRAPH_NEIGHBOR, 0.01f)); // Original low score for neighbors
            }
        }
    }

    private List<Protocol> keywordSearch(IndexSearcher searcher, String queryText, boolean searchPrinciplesOnly) throws IOException {
        List<Protocol> results = new ArrayList<>();
        StandardAnalyzer analyzer = indexManager.getAnalyzer();

        try {
            String[] fields = { LuceneIndexManager.FIELD_TITLE, LuceneIndexManager.FIELD_KEYWORDS, LuceneIndexManager.FIELD_STEPS };
            java.util.Map<String, Float> boosts = new java.util.LinkedHashMap<>();
            boosts.put(LuceneIndexManager.FIELD_TITLE, 4.0f);
            boosts.put(LuceneIndexManager.FIELD_KEYWORDS, 2.0f);
            boosts.put(LuceneIndexManager.FIELD_STEPS, 1.0f);

            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);
            parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);

            Query parsedQuery = parser.parse(org.apache.lucene.queryparser.classic.QueryParserBase.escape(queryText));

            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(parsedQuery, BooleanClause.Occur.MUST);
            
            TermQuery principleFilter = new TermQuery(new Term(LuceneIndexManager.FIELD_CATEGORY, "Principle"));
            if (searchPrinciplesOnly) {
                builder.add(principleFilter, BooleanClause.Occur.FILTER);
            } else {
                builder.add(principleFilter, BooleanClause.Occur.MUST_NOT);
            }
            
            TopDocs docs = searcher.search(builder.build(), MAX_RESULTS);

            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                Protocol p = registry.getProtocolById(doc.get(LuceneIndexManager.FIELD_ID));
                if (p != null) {
                    results.add(p);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Keyword search failed, trying basic term fallback", e);
            TermQuery tq = new TermQuery(new Term(LuceneIndexManager.FIELD_TITLE, queryText.toLowerCase(java.util.Locale.ROOT)));
            TopDocs docs = searcher.search(tq, MAX_RESULTS);
            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                Protocol p = registry.getProtocolById(doc.get(LuceneIndexManager.FIELD_ID));
                if (p != null) results.add(p);
            }
        }
        return results;
    }

    /**
     * Diagnostic tool: Prints top 5 raw hits from Lucene for a query.
     * Replicated logic from EmergencyKnowledgeBase.old.java for debugging.
     */
    public void rawDiagnosticSearch(String queryText) {
        try (IndexReader reader = DirectoryReader.open(indexManager.getIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            if (semanticEngine.getEmbedder() == null) {
                logger.info("  Embedder not initialized.");
                return;
            }
            float[] queryVector = semanticEngine.getEmbedder().embed(queryText);
            Query knnQuery = new KnnFloatVectorQuery(LuceneIndexManager.FIELD_VECTOR, queryVector, 5);
            TopDocs docs = searcher.search(knnQuery, 5);

            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = reader.storedFields().document(sd.doc);
                String id = doc.get(LuceneIndexManager.FIELD_ID);
                String title = doc.get(LuceneIndexManager.FIELD_TITLE);
                Protocol p = registry.getProtocolById(id);

                float pureSemantic = Math.max(0.0f, (sd.score * 2.0f) - 1.0f);
                float lex = (p != null) ? VectorMath.lexicalOverlap(VectorMath.tokenize(queryText), p) : 0.0f;
                float combined = pureSemantic + LEXICAL_WEIGHT * lex;

                logger.info(() -> String.format("  ID: %s | Title: %-25s | Total: %.4f [Sem: %.4f, Lex: %.4f]",
                        id, title, combined, pureSemantic, lex));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Diagnostic search failed", e);
        }
    }
}
