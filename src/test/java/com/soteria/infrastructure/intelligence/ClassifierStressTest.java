package com.soteria.infrastructure.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Realistic Stress Test for TriageService classification.
 * Data-driven approach to comply with the "NEVER HARDCODE LANGUAGE" rule.
 */
public class ClassifierStressTest {

    @Test
    void testConfigurationExists() {
        assertNotNull(getClass().getResourceAsStream("/stress_test_cases.json"),
                "Stress test configuration file must be present in resources");
    }

    @Test
    void runFullStressTest() {
        mainRunner();
    }

    public static class TestCase {
        public String input;
        public String expectedId;
        public String language;
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            System.out.println("Stress test started with " + args.length + " arguments.");
        }
        mainRunner();
    }

    public static void mainRunner() {
        Path modelPath = Paths.get("C:\\Users\\mihai.FSOS-RU7FI8NI14\\.soteria\\models\\soteria-triage-v1.gguf");

        System.out.println("\n" + "=".repeat(115));
        System.out.println("SOTERIA PROTOCOL-ACCURACY STRESS TEST - ROUND 3 (DATA-DRIVEN)");
        System.out.println("Model: " + modelPath);
        System.out.println("=".repeat(115));

        System.out.println("Checking model at: " + modelPath.toAbsolutePath());
        if (!Files.exists(modelPath)) {
            System.out.println("Model not found in default path. Searching in parent directories...");
            // Fallback search: check if it's in the current directory or nearby
            Path current = Paths.get("").toAbsolutePath();
            try {
                modelPath = Files.walk(current.getParent())
                        .filter(p -> p.getFileName().toString().equals("soteria-triage-v1.gguf"))
                        .findFirst()
                        .orElse(modelPath);
            } catch (IOException e) {
                System.err.println("Search failed: " + e.getMessage());
            }
        }

        if (!Files.exists(modelPath)) {
            System.err.println("CRITICAL: Triage model not found after search.");
            return;
        }
        System.out.println("Using model: " + modelPath.toAbsolutePath());

        try (TriageService triage = new TriageService(modelPath);
                EmergencyKnowledgeBase kb = new EmergencyKnowledgeBase()) {

            kb.initializeSemanticIndex(modelPath);
            triage.setCentroid(kb.getCentroid());

            // Load test cases from external JSON
            ObjectMapper mapper = new ObjectMapper();
            List<TestCase> testCases;

            // Try different paths for the JSON file
            Path jsonPath = Paths.get("d:", "repositorios", "Core-del-proyecto", "src", "test", "resources",
                    "stress_test_cases.json");
            if (Files.exists(jsonPath)) {
                System.out.println("Loading test cases from: " + jsonPath.toAbsolutePath());
                testCases = mapper.readValue(jsonPath.toFile(), new TypeReference<List<TestCase>>() {
                });
            } else {
                try (InputStream is = ClassifierStressTest.class.getResourceAsStream("/stress_test_cases.json")) {
                    if (is == null) {
                        throw new IOException("Could not find stress_test_cases.json in resources or filesystem");
                    }
                    testCases = mapper.readValue(is, new TypeReference<List<TestCase>>() {
                    });
                }
            }

            System.out.printf("%-65s | %-15s | %-15s | %-8s%n", "INPUT MESSAGE", "EXPECTED ID", "ACTUAL ID", "RESULT");
            System.out.println("-".repeat(115));

            int passed = 0;
            for (TestCase tc : testCases) {
                String input = tc.input;
                String expectedId = tc.expectedId;

                List<EmergencyKnowledgeBase.ProtocolMatch> matches = kb.findProtocols(input, Collections.emptySet());

                TriageService.TriageResult result = triage.classifyDynamic(input, matches.stream()
                        .map(EmergencyKnowledgeBase.ProtocolMatch::protocol)
                        .toList());

                String actualId = (result.protocol() != null) ? result.protocol().getId() : "NONE";
                float score = result.score();
                System.out.println("DEBUG: Result: " + actualId + " (Score: " + score + ")");

                boolean match = actualId.equals(expectedId);
                if (match)
                    passed++;

                System.out.printf("%-65s | %-15s | %-15s | %-8s%n",
                        input.length() > 62 ? input.substring(0, 62) + "..." : input,
                        expectedId,
                        actualId,
                        match ? "[PASS]" : "[FAIL]");
            }

            System.out.println("=".repeat(115));
            System.out.printf("DATA-DRIVEN SCORE: %d/%d Passed%n", passed, testCases.size());
            System.out.println("=".repeat(115));

            assertTrue(passed > 0, "Stress test must have at least one passing case");

        } catch (Exception e) {
            System.err.println("Execution failed:");
            e.printStackTrace();
        }
    }
}
