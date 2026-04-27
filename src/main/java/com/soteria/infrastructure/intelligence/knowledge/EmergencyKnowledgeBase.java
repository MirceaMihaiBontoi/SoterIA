package com.soteria.infrastructure.intelligence.knowledge;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import de.kherud.llama.LlamaModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Native Emergency Knowledge Base using Apache Lucene (retrieval) and
 * JGraphT (related-protocol discovery). Supports multiple domains including
 * Medical, Fire, Security, Environmental, and Traffic accidents.
 *
 * This class acts as a Facade for the underlying modular components.
 */
public class EmergencyKnowledgeBase implements AutoCloseable, KnowledgeBase {
    private static final Logger logger = Logger.getLogger(EmergencyKnowledgeBase.class.getName());

    private final ProtocolRegistry registry;
    private final SemanticEngine semanticEngine;
    private final KnowledgeGraphManager graphManager;
    private final LuceneIndexManager indexManager;
    private final EmergencySearcher searcher;

    private FileHandler diagHandler;

    public EmergencyKnowledgeBase(String protocolsPath, Path indexPath, SystemCapability capability) {
        // Ensure path starts with delimiter for classpath resources in a robust way
        String normalizedProtocolsPath = protocolsPath;
        if (!normalizedProtocolsPath.startsWith("/") && !normalizedProtocolsPath.startsWith("\\")) {
            normalizedProtocolsPath = "/".concat(normalizedProtocolsPath);
        }

        this.registry = new ProtocolRegistry(normalizedProtocolsPath);
        this.semanticEngine = new SemanticEngine(capability, indexPath);
        this.graphManager = new KnowledgeGraphManager(registry);
        this.indexManager = new LuceneIndexManager(indexPath);
        this.searcher = new EmergencySearcher(indexManager, registry, semanticEngine, graphManager);

        setupDiagnostics(normalizedProtocolsPath);
        registry.loadProtocols();
        graphManager.buildKnowledgeGraph();

        // Initial keyword-only indexing only if index doesn't exist
        try {
            if (!registry.getProtocols().isEmpty() && !indexManager.indexExists()) {
                indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
            } else {
                logger.info("Persistent index detected or no protocols to index. Skipping initial indexing.");
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error checking index existence, proceeding with safety indexing", e);
            indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
        }
    }

    public EmergencyKnowledgeBase() {
        this("/data/protocols/", Paths.get(System.getProperty("java.io.tmpdir"), "soteria-index-stable"),
                new SystemCapability());
    }

    public float[] getCentroid() {
        return semanticEngine.getCentroid();
    }

    public synchronized void initializeSemanticIndex(Path modelPath) {
        logger.log(Level.INFO, "Initializing semantic embedder with model: {0}", modelPath);
        indexManager.clearIndex();
        semanticEngine.loadEmbedder(modelPath);
        indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
    }

    public synchronized void setEmbedder(LlamaModel embedder) {
        semanticEngine.setEmbedder(embedder);
        logger.info("Shared embedder (CT-XLMR-SE) injected into Knowledge Base.");

        if (indexManager.hasSemanticVectors() && semanticEngine.loadCentroid()) {
            logger.info("Semantic vectors and centroid loaded from persistent index.");
        } else {
            // No vectors, or centroid sidecar missing/corrupt — rebuild both atomically.
            indexManager.clearIndex();
            indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
        }
    }

    /**
     * Injects a mock embedder for tests to avoid native library crashes.
     */
    public synchronized void setTestEmbedder(SemanticEngine.VectorEmbedder testEmbedder) {
        semanticEngine.setTestEmbedder(testEmbedder);
        indexManager.clearIndex();
        indexManager.indexProtocols(registry.getProtocols(), semanticEngine);
    }

    private void setupDiagnostics(String protocolsPath) {
        try {
            Path logPath = Paths.get("logs/kb_diagnostics.log");
            if (!Files.exists(logPath.getParent())) {
                Files.createDirectories(logPath.getParent());
            }
            // FileHandler rolls to .1/.2/.lck siblings when prior runs leave locks
            // behind. Sweep them so the directory shows a single fresh log per session.
            try (var entries = Files.list(logPath.getParent())) {
                entries.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.matches("kb_diagnostics\\.log\\.\\d+") || name.endsWith(".lck");
                }).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException _) { /* best-effort */ }
                });
            }
            Files.writeString(logPath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            this.diagHandler = new FileHandler(logPath.toString(), false);
            this.diagHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(this.diagHandler);
            logger.log(Level.INFO, "EmergencyKnowledgeBase diagnostics initialized for path: {0}", protocolsPath);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to setup KB diagnostics file", e);
        }
    }

    public List<ProtocolMatch> findProtocols(String queryText) {
        return findProtocols(queryText, Set.of(), false);
    }

    public ProtocolMatch findTopMatch(String queryText) {
        List<ProtocolMatch> matches = findProtocols(queryText);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public List<ProtocolMatch> findProtocols(String queryText, Set<String> rejectedIds) {
        return findProtocols(queryText, rejectedIds, false);
    }

    public List<ProtocolMatch> findProtocols(String queryText, Set<String> rejectedIds, boolean searchPrinciplesOnly) {
        return searcher.search(queryText, rejectedIds, searchPrinciplesOnly);
    }

    public List<Protocol> getRelatedProtocols(String protocolId) {
        return graphManager.getRelatedProtocols(protocolId);
    }

    public List<Protocol> getAllProtocols() {
        return registry.getProtocols();
    }

    public Protocol getProtocolById(String id) {
        return registry.getProtocolById(id);
    }

    @Override
    public void close() {
        try {
            semanticEngine.close();
        } catch (Exception e) {
            logger.log(Level.FINE, "Error closing semantic engine", e);
        }
        try {
            indexManager.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to close Lucene index", e);
        }
        if (diagHandler != null) {
            logger.removeHandler(diagHandler);
            diagHandler.close();
        }
    }

    /**
     * Restored advanced diagnostic search to show raw Lucene hits.
     */
    public void diagnosticSearch(String queryText) {
        searcher.rawDiagnosticSearch(queryText);
    }
}
