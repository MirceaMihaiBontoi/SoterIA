package com.soteria.infrastructure.intelligence.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RagDiagnostic {
    private static final Logger logger = Logger.getLogger(RagDiagnostic.class.getName());

    public static void main(String[] args) {
        logger.info("--- SoterIA RAG Diagnostic ---");
        
        // Build portable path using user.home
        String userHome = System.getProperty("user.home");
        Path defaultPath = Paths.get(userHome, ".soteria", "models", "paraphrase-multilingual-MiniLM-L12-118M-v2-F16.gguf");
        String modelPath = defaultPath.toString();
        
        if (args.length > 0) modelPath = args[0];
        
        try (EmergencyKnowledgeBase kb = new EmergencyKnowledgeBase()) {
            logger.info("Initializing Semantic Index...");
            kb.initializeSemanticIndex(Paths.get(modelPath));
            
            String[] testQueries = {"hola", "me duele la cabeza", "incendio en la cocina"};
            
            for (String q : testQueries) {
                logger.info(() -> String.format("%nQuery: '%s'", q));
                // Accessing internal search for diagnostic purposes
                kb.diagnosticSearch(q);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Diagnostic failed", e);
        }
    }
}
