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
import org.apache.lucene.store.FSDirectory;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Native Emergency Knowledge Base using Apache Lucene (retrieval) and
 * JGraphT (related-protocol discovery). Supports multiple domains including
 * Medical, Fire, Security, Environmental, and Traffic accidents.
 */
public class EmergencyKnowledgeBase implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(EmergencyKnowledgeBase.class.getName());

    public record ProtocolMatch(Protocol protocol, String source, float score) {}

    private static final String SOURCE_SEMANTIC = "SEMANTIC";
    private static final String SOURCE_CANDIDATE = "CANDIDATE";
    private static final String SOURCE_GRAPH_BOOSTED = "GRAPH_BOOSTED";
    private static final String SOURCE_GRAPH_NEIGHBOR = "GRAPH_NEIGHBOR";
    private static final String SOURCE_KEYWORD = "KEYWORD";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 10;
    private static final String INDEX_FILE = "index.json";
    private static final String RESOURCE_DELIMITER = "/";
    private static final String FIELD_ID = "id";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_KEYWORDS = "keywords";
    private static final String FIELD_STEPS = "steps";
    private static final String FIELD_VECTOR = "vector";

    private final String protocolsPath;
    private final SystemCapability capability;
    private final Path indexDirPath;

    private List<Protocol> protocols;
    private Map<String, Protocol> protocolsById;
    private Directory index;
    private final StandardAnalyzer analyzer;
    private final Graph<String, DefaultEdge> knowledgeGraph;

    private LlamaModel embedder;
    private float[] centroid;


    public EmergencyKnowledgeBase(String protocolsPath, Path indexPath, SystemCapability capability) {
        this.capability = capability;
        this.indexDirPath = indexPath;
        // Ensure path starts with delimiter for classpath resources
        this.protocolsPath = protocolsPath.startsWith(RESOURCE_DELIMITER) ? protocolsPath : RESOURCE_DELIMITER + protocolsPath;
        this.analyzer = new StandardAnalyzer();
        try {
            this.index = FSDirectory.open(indexPath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not open persistent index directory", e);
            this.index = new ByteBuffersDirectory(); // Fallback
        }
        this.knowledgeGraph = new SimpleGraph<>(DefaultEdge.class);
        
        setupDiagnostics();
        loadProtocols();
        buildKnowledgeGraph();
        
        // Initial keyword-only indexing only if index doesn't exist
        try {
            if (protocols != null && !protocols.isEmpty() && !DirectoryReader.indexExists(index)) {
                indexProtocols(null); 
            } else {
                logger.info("Persistent index detected or no protocols to index. Skipping initial indexing.");
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error checking index existence, proceeding with safety indexing", e);
            indexProtocols(null);
        }
    }

    public EmergencyKnowledgeBase() {
        this("/data/protocols/", java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "soteria-index-stable"), new SystemCapability());
    }

    public synchronized void initializeSemanticIndex(Path modelPath) {
        logger.log(Level.INFO, "Initializing semantic embedder with model: {0}", modelPath);

        try {
            if (DirectoryReader.indexExists(index)) {
                try (IndexReader reader = DirectoryReader.open(index)) {
                    // Check if the index already contains at least one document with vectors
                    boolean hasVectors = false;
                    for (LeafReaderContext context : reader.leaves()) {
                        if (context.reader().getFloatVectorValues(FIELD_VECTOR) != null) {
                            hasVectors = true;
                            break;
                        }
                    }
                    
                    if (hasVectors) {
                        logger.info("Semantic vectors already exist in persistent index. Skipping re-indexing.");
                        loadEmbedder(modelPath);
                        loadCentroid();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not verify semantic index existence, proceeding with fresh embedding", e);
        }

        loadEmbedder(modelPath);
        indexProtocols(this.embedder);
    }

    private void loadEmbedder(Path modelPath) {
        if (this.embedder != null) return;
        try {
            int threads = capability.getIdealThreadCount();
            ModelParameters params = new ModelParameters()
                    .setModel(modelPath.toAbsolutePath().toString())
                    .setThreads(threads)
                    .setThreadsBatch(threads)
                    .setGpuLayers(-1) // Default to CPU for maximum portability across PC/Android
                    .enableEmbedding();
            this.embedder = new LlamaModel(params);
            logger.log(Level.INFO, () -> String.format("Semantic embedder initialized with %d threads", threads));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load embedding model", e);
        }
    }

    private void setupDiagnostics() {
        try {
            // Overwrite log file on each restart (append = false)
            FileHandler handler = new FileHandler("logs/kb_diagnostics.log", false);
            handler.setFormatter(new SimpleFormatter());
            logger.addHandler(handler);
            logger.log(Level.INFO, "EmergencyKnowledgeBase diagnostics initialized for path: {0}", protocolsPath);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to setup KB diagnostics file", e);
        }
    }

    /**
     * Loads protocols from category-based JSON files based on the manifest index.
     */
    private void loadProtocols() {
        this.protocols = new ArrayList<>();
        this.protocolsById = new LinkedHashMap<>();

        String indexPath = protocolsPath + INDEX_FILE;
        logger.log(Level.INFO, "Attempting to load index from: {0}", indexPath);
        
        try (InputStream indexStream = getClass().getResourceAsStream(indexPath)) {
            if (indexStream == null) {
                logger.log(Level.SEVERE, "CRITICAL: Protocols index.json not found at {0}", indexPath);
                return;
            }

            List<String> categoryFiles = mapper.readValue(indexStream, new TypeReference<List<String>>() {});
            logger.log(Level.INFO, "Found {0} categories in index.", categoryFiles == null ? 0 : categoryFiles.size());
            
            if (categoryFiles != null) {
                for (String fileName : categoryFiles) {
                    loadCategoryProtocols(fileName);
                }
            }

            logger.info(() -> "Total protocols loaded: " + protocols.size());
        } catch (Exception e) {
            logger.log(Level.SEVERE, e, () -> "Failed to load emergency protocols via manifest: " + indexPath);
        }
    }

    private void loadCategoryProtocols(String fileName) {
        String fullPath = protocolsPath + fileName;
        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is == null) {
                logger.log(Level.WARNING, "Category file not found: {0}", fullPath);
                return;
            }
            List<Protocol> categoryList = mapper.readValue(is, new TypeReference<List<Protocol>>() {});
            if (categoryList != null) {
                for (Protocol p : categoryList) {
                    if (p.getId() != null) {
                        this.protocols.add(p);
                        this.protocolsById.put(p.getId(), p);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> "Failed to parse category file: " + fullPath);
        }
    }

    private synchronized void indexProtocols(LlamaModel lEmbedder) {
        logger.log(Level.INFO, () -> String.format("Indexing %d protocols (Embedder: %s)...",
                protocols.size(), (lEmbedder != null ? "Ready" : "None")));

        List<Protocol> valid = new ArrayList<>();
        List<float[]> rawVectors = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        if (lEmbedder != null) {
            processSemanticIndexing(lEmbedder, valid, rawVectors, contents);
        } else {
            for (Protocol p : protocols) {
                if (p.getId() != null && p.getTitle() != null) {
                    valid.add(p);
                }
            }
        }

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (IndexWriter writer = new IndexWriter(index, config)) {
            writeDocumentsToIndex(writer, lEmbedder, valid, rawVectors, contents);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Index process failed", e);
        }
    }

    private void processSemanticIndexing(LlamaModel lEmbedder, List<Protocol> valid, List<float[]> rawVectors, List<String> contents) {
        for (Protocol p : protocols) {
            if (p.getId() == null || p.getTitle() == null) continue;
            String content = String.format("%s | %s | %s",
                    p.getTitle(), p.getCategory(),
                    p.getKeywords() == null ? "" : String.join(", ", p.getKeywords()));
            float[] v = lEmbedder.embed(content);
            valid.add(p);
            rawVectors.add(v);
            contents.add(content);
        }
        this.centroid = computeCentroid(rawVectors);
        logger.log(Level.INFO, () -> String.format(
                "[RAG-CENTROID] computed from %d vectors, magnitude=%.4f",
                rawVectors.size(), magnitude(this.centroid)));
        saveCentroid();
    }

    private void writeDocumentsToIndex(IndexWriter writer, LlamaModel lEmbedder, List<Protocol> valid, List<float[]> rawVectors, List<String> contents) throws IOException {
        int count = 0;
        for (int i = 0; i < valid.size(); i++) {
            Protocol p = valid.get(i);
            Document doc = createDocument(p);

            if (lEmbedder != null) {
                addSemanticVectorToDoc(doc, p, rawVectors.get(i), contents.get(i));
            }

            writer.addDocument(doc);
            count++;
        }
        int finalCount = count;
        logger.log(Level.INFO, () -> "Indexing finished: " + finalCount + " docs created.");
    }

    private Document createDocument(Protocol p) {
        Document doc = new Document();
        doc.add(new StringField(FIELD_ID, p.getId(), Field.Store.YES));
        doc.add(new TextField(FIELD_TITLE, p.getTitle(), Field.Store.YES));
        if (p.getSteps() != null) {
            doc.add(new TextField(FIELD_STEPS, String.join(" ", p.getSteps()), Field.Store.NO));
        }
        if (p.getKeywords() != null) {
            doc.add(new TextField(FIELD_KEYWORDS, String.join(" ", p.getKeywords()), Field.Store.NO));
        }
        return doc;
    }

    private void addSemanticVectorToDoc(Document doc, Protocol p, float[] rawVector, String content) {
        float[] centered = normalize(subtract(rawVector, centroid));
        logger.log(Level.INFO, () -> String.format(
                "[RAG-INDEX] id=%s title='%s' dims=%d raw_mag=%.4f centered_mag=%.4f first3=[%.4f, %.4f, %.4f] content='%s'",
                p.getId(), p.getTitle(), centered.length,
                magnitude(rawVector), magnitude(centered),
                centered[0], centered[1], centered[2], content));
        doc.add(new KnnVectorField(FIELD_VECTOR, centered, VectorSimilarityFunction.COSINE));
    }

    /**
     * Builds an undirected relationship graph. Two protocols are linked when:
     *   (a) they share the same category, or
     *   (b) one protocol's steps explicitly reference another by title.
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
     * Entry point for finding protocols. Uses Pure Semantic Retrieval (Vector Search)
     * enhanced by Knowledge Graph validation and enrichment.
     */
    public List<ProtocolMatch> findProtocols(String queryText) {
        return findProtocols(queryText, Set.of());
    }

    public List<ProtocolMatch> findProtocols(String queryText, Set<String> rejectedIds) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Use pure query text for retrieval to avoid polluting the vector space with previous AI suspicions
        String optimizedQuery = queryText;

        try (IndexReader reader = DirectoryReader.open(index)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            List<ProtocolMatch> matches = new ArrayList<>();

            if (embedder != null) {
                matches = semanticSearch(searcher, optimizedQuery, rejectedIds);
                enrichWithRelated(matches);
            } else {
                logger.warning("Embedder unavailable. Using Keyword search safety net.");
                List<Protocol> results = keywordSearch(searcher, optimizedQuery);
                for (Protocol p : results) {
                    if (!rejectedIds.contains(p.getId())) {
                        matches.add(new ProtocolMatch(p, SOURCE_KEYWORD, 1.0f));
                    }
                }
            }

            return matches;
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> "Retrieval failed for query: " + optimizedQuery);
            return new ArrayList<>();
        }
    }

    private List<ProtocolMatch> semanticSearch(IndexSearcher searcher, String queryText, Set<String> rejectedIds) throws IOException {
        List<ProtocolMatch> results = new ArrayList<>();
        logger.log(Level.INFO, () -> String.format("========== [RAG-QUERY] '%s' ==========", queryText));

        long tEmbed = System.nanoTime();
        float[] rawQueryVector = embedder.embed(queryText);
        float[] queryVector = (centroid != null)
                ? normalize(subtract(rawQueryVector, centroid))
                : rawQueryVector;
        long embedMs = (System.nanoTime() - tEmbed) / 1_000_000L;
        logger.log(Level.INFO, () -> String.format(
            "[RAG-QUERY] dims=%d raw_mag=%.4f centered_mag=%.4f embed_ms=%d centered=%b",
            queryVector.length, magnitude(rawQueryVector), magnitude(queryVector),
            embedMs, (centroid != null)));

        Query knnQuery = new KnnVectorQuery(FIELD_VECTOR, queryVector, 10);
        Query finalQuery = knnQuery;
        if (!rejectedIds.isEmpty()) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(knnQuery, BooleanClause.Occur.MUST);
            for (String id : rejectedIds) {
                builder.add(new TermQuery(new Term(FIELD_ID, id)), BooleanClause.Occur.MUST_NOT);
            }
            finalQuery = builder.build();
        }

        long tSearch = System.nanoTime();
        TopDocs docs = searcher.search(finalQuery, 10);
        long searchMs = (System.nanoTime() - tSearch) / 1_000_000L;
        logger.log(Level.INFO, "[RAG-QUERY] knn_search_ms={0} hits={1}",
            new Object[]{searchMs, docs.scoreDocs.length});

        Set<String> queryTokens = tokenize(queryText);
        List<float[]> scored = new ArrayList<>();

        int rank = 0;
        for (ScoreDoc scoreDoc : docs.scoreDocs) {
            rank++;
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            String id = doc.get(FIELD_ID);
            String title = doc.get(FIELD_TITLE);
            Protocol p = protocolsById.get(id);
            if (p == null) continue;

            // Re-scale Lucene's (1+cos)/2 score to get true cosine similarity [0, 1]
            float pureSemantic = Math.max(0.0f, (scoreDoc.score * 2.0f) - 1.0f);
            float lexical = lexicalOverlap(queryTokens, p);
            float combined = pureSemantic + LEXICAL_WEIGHT * lexical;

            logger.log(Level.INFO, String.format(
                "[RAG-HIT-RAW] rank=%d lucene=%.4f pure=%.4f lex=%.4f combined=%.4f id=%s title='%s'",
                rank, scoreDoc.score, pureSemantic, lexical, combined, id, title));

            scored.add(new float[]{scoreDoc.doc, pureSemantic, lexical, combined});
        }

        scored.sort((a, b) -> Float.compare(b[3], a[3]));

        int finalRank = 0;
        for (float[] row : scored) {
            finalRank++;
            Document doc = searcher.storedFields().document((int) row[0]);
            String id = doc.get(FIELD_ID);
            String title = doc.get(FIELD_TITLE);
            Protocol p = protocolsById.get(id);
            if (p == null) continue;

            float combined = row[3];
            String classification;
            if (combined >= 0.40f) { // Real similarity > 40%
                classification = SOURCE_SEMANTIC;
                results.add(new ProtocolMatch(p, SOURCE_SEMANTIC, combined));
            } else if (combined >= 0.20f) { // Real similarity > 20%
                classification = SOURCE_CANDIDATE;
                results.add(new ProtocolMatch(p, SOURCE_CANDIDATE, combined));
            } else {
                classification = "DROPPED";
            }
            final int fRank = finalRank;
            final float fCombined = combined;
            final String fClassification = classification;
            final String fId = id;
            final String fTitle = title;
            final float[] fRow = row;
            logger.log(Level.INFO, () -> String.format(
                "[RAG-HIT] rerank=%d combined=%.4f cos=%.4f lex=%.4f status=%s id=%s title='%s'",
                fRank, fCombined, fRow[1], fRow[2], fClassification, fId, fTitle));
        }
        logger.log(Level.INFO, "[RAG-QUERY] kept_semantic={0} kept_candidate={1}",
            new Object[]{
                results.stream().filter(m -> m.source().equals(SOURCE_SEMANTIC)).count(),
                results.stream().filter(m -> m.source().equals(SOURCE_CANDIDATE)).count()
            });
        return results;
    }

    private static final float LEXICAL_WEIGHT = 0.2f;

    private static Set<String> tokenize(String text) {
        Set<String> out = new java.util.HashSet<>();
        if (text == null) return out;
        String norm = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        for (String tok : norm.split("[^a-z0-9]+")) {
            if (tok.length() >= 3) out.add(tok);
        }
        return out;
    }

    private static float lexicalOverlap(Set<String> queryTokens, Protocol p) {
        if (queryTokens.isEmpty()) return 0f;
        Set<String> docTokens = new java.util.HashSet<>();
        docTokens.addAll(tokenize(p.getTitle()));
        if (p.getKeywords() != null) {
            for (String k : p.getKeywords()) docTokens.addAll(tokenize(k));
        }
        int hits = 0;
        for (String q : queryTokens) if (docTokens.contains(q)) hits++;
        return (float) hits / (float) queryTokens.size();
    }

    private static float magnitude(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        return (float) Math.sqrt(sum);
    }

    private static float[] subtract(float[] a, float[] b) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }

    private static float[] normalize(float[] v) {
        float m = magnitude(v);
        if (m == 0f) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / m;
        return out;
    }

    private static float[] computeCentroid(List<float[]> vectors) {
        if (vectors.isEmpty()) return new float[0];
        int dims = vectors.get(0).length;
        float[] sum = new float[dims];
        for (float[] v : vectors) {
            for (int i = 0; i < dims; i++) sum[i] += v[i];
        }
        float n = vectors.size();
        for (int i = 0; i < dims; i++) sum[i] /= n;
        return sum;
    }

    private Path centroidPath() {
        return indexDirPath.resolve("centroid.bin");
    }

    private void saveCentroid() {
        if (centroid == null) return;
        try {
            java.nio.file.Files.createDirectories(indexDirPath);
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    java.nio.file.Files.newOutputStream(centroidPath()))) {
                out.writeInt(centroid.length);
                for (float f : centroid) out.writeFloat(f);
            }
            logger.log(Level.INFO, "[RAG-CENTROID] saved to {0}", centroidPath());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save centroid", e);
        }
    }

    private void loadCentroid() {
        Path p = centroidPath();
        if (!java.nio.file.Files.exists(p)) {
            logger.warning("[RAG-CENTROID] sidecar missing, mean-centering disabled for this run");
            return;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                java.nio.file.Files.newInputStream(p))) {
            int dims = in.readInt();
            float[] c = new float[dims];
            for (int i = 0; i < dims; i++) c[i] = in.readFloat();
            this.centroid = c;
            logger.log(Level.INFO, () -> String.format(
                "[RAG-CENTROID] loaded from %s dims=%d magnitude=%.4f",
                p, dims, magnitude(c)));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to load centroid", e);
        }
    }

    private List<Protocol> keywordSearch(IndexSearcher searcher, String queryText) throws IOException {
        List<Protocol> results = new ArrayList<>();
        if (embedder == null) {
            logger.warning("Embedder unavailable. Using Keyword search.");
        }
        
        try {
            String[] fields = {FIELD_TITLE, FIELD_KEYWORDS, FIELD_STEPS};
            Map<String, Float> boosts = new LinkedHashMap<>();
            boosts.put(FIELD_TITLE, 4.0f);
            boosts.put(FIELD_KEYWORDS, 2.0f);
            boosts.put(FIELD_STEPS, 1.0f);
            
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);
            parser.setDefaultOperator(MultiFieldQueryParser.Operator.OR);
            
            Query query = parser.parse(MultiFieldQueryParser.escape(queryText));
            TopDocs docs = searcher.search(query, MAX_RESULTS);
            
            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                Protocol p = protocolsById.get(doc.get(FIELD_ID));
                if (p != null) {
                    results.add(p);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Keyword search failed, trying basic term fallback", e);
            // Absolute fallback to simple title term match
            TermQuery tq = new TermQuery(new Term(FIELD_TITLE, queryText.toLowerCase(Locale.ROOT)));
            TopDocs docs = searcher.search(tq, MAX_RESULTS);
            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                results.add(protocolsById.get(doc.get(FIELD_ID)));
            }
        }
        return results;
    }

    private void enrichWithRelated(List<ProtocolMatch> matches) {
        if (matches.isEmpty()) return;

        // 1. Extract high-confidence hits to use as 'anchors' for the Graph
        List<ProtocolMatch> anchors = matches.stream()
                .filter(m -> m.source().equals(SOURCE_SEMANTIC))
                .toList();

        if (anchors.isEmpty()) {
            // If no primary hits, we clear candidates (as they lack a graph anchor) and return
            matches.removeIf(m -> m.source().equals(SOURCE_CANDIDATE));
            return;
        }

        List<ProtocolMatch> additions = new ArrayList<>();
        
        // 2. Promotion: Promote CANDIDATE matches to GRAPH_BOOSTED if they are neighbors of an anchor
        for (int i = 0; i < matches.size(); i++) {
            ProtocolMatch match = matches.get(i);
            if (match.source().equals(SOURCE_CANDIDATE)) {
                boolean isNeighborOfAnchor = anchors.stream()
                        .anyMatch(a -> knowledgeGraph.containsEdge(a.protocol().getId(), match.protocol().getId()));
                
                if (isNeighborOfAnchor) {
                    logger.info("Graph: Promoting '" + match.protocol().getTitle() + "' to GRAPH_BOOSTED (neighbor of anchor)");
                    matches.set(i, new ProtocolMatch(match.protocol(), SOURCE_GRAPH_BOOSTED, match.score()));
                }
            }
        }
        
        // Clean up remaining unloved candidates
        matches.removeIf(m -> m.source().equals(SOURCE_CANDIDATE));

        // 3. Expansion: Add direct neighbors of the TOP anchor if they aren't already matched
        Protocol topAnchor = anchors.get(0).protocol();
        for (Protocol related : getRelatedProtocols(topAnchor.getId())) {
            if (matches.size() + additions.size() >= MAX_RESULTS) break;
            
            boolean alreadyHit = matches.stream().anyMatch(m -> m.protocol().getId().equals(related.getId()));
            if (!alreadyHit) {
                logger.info("Graph: Adding neighbor '" + related.getTitle() + "' (Relation to " + topAnchor.getTitle() + ")");
                additions.add(new ProtocolMatch(related, SOURCE_GRAPH_NEIGHBOR, 0.01f));
            }
        }
        
        matches.addAll(additions);
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

    public Protocol getProtocolById(String id) {
        if (protocolsById == null || id == null) return null;
        return protocolsById.get(id);
    }

    @Override
    public void close() {
        try {
            if (embedder != null) embedder.close();
        } catch (Throwable t) {
            logger.log(Level.FINE, "Silent failure during native embedder cleanup", t);
        }
        try {
            if (index != null) index.close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to close Lucene index directory", e);
        }
    }

    /**
     * Diagnostic tool: Prints top 5 raw hits from Lucene for a query.
     */
    public void diagnosticSearch(String queryText) {
        try (org.apache.lucene.index.IndexReader reader = org.apache.lucene.index.DirectoryReader.open(index)) {
            org.apache.lucene.search.IndexSearcher searcher = new org.apache.lucene.search.IndexSearcher(reader);
            if (embedder == null) {
                logger.info("  Embedder not initialized.");
                return;
            }
            float[] queryVector = embedder.embed(queryText);
            org.apache.lucene.search.Query knnQuery = new org.apache.lucene.search.KnnVectorQuery(FIELD_VECTOR, queryVector, 5);
            org.apache.lucene.search.TopDocs docs = searcher.search(knnQuery, 5);
            
            for (org.apache.lucene.search.ScoreDoc sd : docs.scoreDocs) {
                org.apache.lucene.document.Document doc = reader.storedFields().document(sd.doc);
                String id = doc.get(FIELD_ID);
                String title = doc.get(FIELD_TITLE);
                Protocol p = protocolsById.get(id);
                
                float pureSemantic = Math.max(0.0f, (sd.score * 2.0f) - 1.0f);
                float lex = (p != null) ? lexicalOverlap(tokenize(queryText), p) : 0.0f;
                float combined = pureSemantic + LEXICAL_WEIGHT * lex;
                
                logger.info(() -> String.format("  ID: %s | Title: %-25s | Total: %.4f [Sem: %.4f, Lex: %.4f]", 
                    id, title, combined, pureSemantic, lex));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

