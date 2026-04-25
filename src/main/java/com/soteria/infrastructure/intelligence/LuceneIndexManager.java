package com.soteria.infrastructure.intelligence;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Lucene index operations, including indexing and directory management.
 */
public class LuceneIndexManager implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(LuceneIndexManager.class.getName());

    public static final String FIELD_ID = "id";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_KEYWORDS = "keywords";
    public static final String FIELD_STEPS = "steps";
    public static final String FIELD_VECTOR = "vector";
    public static final String FIELD_CATEGORY = "category";
    private final StandardAnalyzer analyzer;
    private Directory index;

    public LuceneIndexManager(Path indexDirPath) {
        this.analyzer = new StandardAnalyzer();
        try {
            this.index = FSDirectory.open(indexDirPath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not open persistent index directory", e);
            this.index = new ByteBuffersDirectory();
        }
    }

    public Directory getIndex() {
        return index;
    }

    public StandardAnalyzer getAnalyzer() {
        return analyzer;
    }

    public boolean indexExists() throws IOException {
        return DirectoryReader.indexExists(index);
    }

    public void clearIndex() {
        try {
            if (index instanceof FSDirectory) {
                IndexWriterConfig config = new IndexWriterConfig(analyzer);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                try (IndexWriter writer = new IndexWriter(index, config)) {
                    writer.deleteAll();
                    writer.commit();
                }
            }
        } catch (IOException e) {
            logger.warning("Could not clear index: " + e.getMessage());
        }
    }

    public boolean hasSemanticVectors() {
        try (IndexReader reader = DirectoryReader.open(index)) {
            for (LeafReaderContext context : reader.leaves()) {
                if (context.reader().getFloatVectorValues(FIELD_VECTOR) != null) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to check semantic vectors", e);
        }
        return false;
    }

    public void indexProtocols(List<Protocol> protocols, SemanticEngine semanticEngine) {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Indexing {0} protocols (Embedder: {1})...",
                new Object[]{protocols.size(), (semanticEngine.getEmbedder() != null ? "Ready" : "None")});
        }

        List<Protocol> validProtocols = new ArrayList<>();
        List<float[]> rawVectors = new ArrayList<>();

        if (semanticEngine.getEmbedder() != null) {
            prepareVectors(protocols, semanticEngine, validProtocols, rawVectors);
            semanticEngine.computeAndSaveCentroid(rawVectors);
        } else {
            filterValidProtocols(protocols, validProtocols);
        }

        writeToIndex(validProtocols, rawVectors, semanticEngine);
    }

    private void prepareVectors(List<Protocol> protocols, SemanticEngine semanticEngine,
                               List<Protocol> validProtocols, List<float[]> rawVectors) {
        for (Protocol p : protocols) {
            if (p.getId() == null || p.getTitle() == null) continue;
            String content = buildIndexContent(p);
            float[] v = semanticEngine.getEmbedder().embed(content);
            validProtocols.add(p);
            rawVectors.add(v);
        }
    }

    private String buildIndexContent(Protocol p) {
        String role = p.getId().endsWith("_VIC") ? "[VIC]" : p.getId().endsWith("_WIT") ? "[WIT]" : "";
        String keywordsStr = p.getKeywords() == null ? "" : String.join(" ", p.getKeywords());
        return String.format("%s %s %s", role, p.getTitle(), keywordsStr).trim();
    }

    private void filterValidProtocols(List<Protocol> protocols, List<Protocol> validProtocols) {
        for (Protocol p : protocols) {
            if (p.getId() != null && p.getTitle() != null) {
                validProtocols.add(p);
            }
        }
    }

    private void writeToIndex(List<Protocol> validProtocols, List<float[]> rawVectors, SemanticEngine semanticEngine) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (IndexWriter writer = new IndexWriter(index, config)) {
            for (int i = 0; i < validProtocols.size(); i++) {
                Protocol p = validProtocols.get(i);
                Document doc = createDocument(p);
                if (semanticEngine.getEmbedder() != null && i < rawVectors.size()) {
                    float[] raw = rawVectors.get(i);
                    float[] centroid = semanticEngine.getCentroid();
                    float[] vectorToStore = (centroid != null)
                        ? VectorMath.normalize(VectorMath.subtract(raw, centroid))
                        : VectorMath.normalize(raw);
                    doc.add(new KnnFloatVectorField(FIELD_VECTOR, vectorToStore, VectorSimilarityFunction.COSINE));
                }
                writer.addDocument(doc);
            }
            logger.log(Level.INFO, "Indexing finished: {0} docs created.", validProtocols.size());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Index process failed", e);
        }
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
        if (p.getCategory() != null) {
            doc.add(new StringField(FIELD_CATEGORY, p.getCategory(), Field.Store.YES));
        }
        return doc;
    }

    @Override
    public void close() throws IOException {
        if (index != null) {
            index.close();
        }
    }
}
