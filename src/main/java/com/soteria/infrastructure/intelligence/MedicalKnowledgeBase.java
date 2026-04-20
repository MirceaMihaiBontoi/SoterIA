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
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native Medical Knowledge Base using Apache Lucene and JGraphT.
 * Provides fast retrieval of first aid protocols without network access.
 */
public class MedicalKnowledgeBase {
    private static final Logger logger = Logger.getLogger(MedicalKnowledgeBase.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private List<Protocol> protocols;
    private Directory index;
    private StandardAnalyzer analyzer;
    private Graph<String, DefaultEdge> knowledgeGraph;

    public MedicalKnowledgeBase() {
        this.analyzer = new StandardAnalyzer();
        this.index = new ByteBuffersDirectory();
        this.knowledgeGraph = new SimpleGraph<>(DefaultEdge.class);
        loadProtocols();
        indexProtocols();
    }

    private void loadProtocols() {
        try (InputStream is = getClass().getResourceAsStream("/data/medical_protocols.json")) {
            if (is == null) {
                logger.severe("CRITICAL: medical_protocols.json not found in resources.");
                this.protocols = new ArrayList<>();
                return;
            }
            this.protocols = mapper.readValue(is, new TypeReference<List<Protocol>>() {});
            logger.info("Loaded " + protocols.size() + " medical protocols.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to parse medical protocols JSON", e);
            this.protocols = new ArrayList<>();
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
                
                // Populate Knowledge Graph nodes
                knowledgeGraph.addVertex(p.getId());
            }
            logger.info("Successfully indexed all protocols in-memory.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lucene indexing failed", e);
        }
    }

    /**
     * Searches for protocols matching the user query.
     */
    public List<Protocol> findProtocols(String queryText) {
        List<Protocol> results = new ArrayList<>();
        if (queryText == null || queryText.trim().isEmpty()) return results;

        try (IndexReader reader = DirectoryReader.open(index)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            
            String[] fields = {"title", "keywords"};
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);
            Query query = parser.parse(queryText + "*"); // Appending wildcard for partial matches
            
            TopDocs docs = searcher.search(query, 3);
            for (ScoreDoc sd : docs.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                String id = doc.get("id");
                protocols.stream()
                        .filter(p -> p.getId().equals(id))
                        .findFirst()
                        .ifPresent(results::add);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> "Retrieval failed for query: " + queryText);
        }
        return results;
    }

    public List<Protocol> getAllProtocols() {
        return new ArrayList<>(protocols);
    }
}
