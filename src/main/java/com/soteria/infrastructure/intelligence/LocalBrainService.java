package com.soteria.infrastructure.intelligence;

import com.soteria.core.exception.AIEngineException;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.args.MiroStat;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;

/**
 * Local LLM inference service powered by llama.cpp (GGUF format).
 * Offline-first, CPU + GPU (Metal/Vulkan) depending on the native build.
 */
public class LocalBrainService implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(LocalBrainService.class.getName());
    private static final String DASHBOARD_BORDER = "====================================================";
    private static final String HEADER_END_TAG = "[END]";

    private final Path modelFile;
    private final SystemCapability capability;
    private LlamaModel model;

    public LocalBrainService(Path modelFile, SystemCapability capability) {
        this.modelFile = modelFile;
        this.capability = capability;
        initializeModel();
        setupPromptLogging();
    }

    private void setupPromptLogging() {
        try {
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            // Ensure a fresh start on every app launch (autolimpia)
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get("logs/ai_conversation.log"), 
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("--- SoterIA AI Conversation Log (Autocleaned) ---\n");
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to initialize AI conversation logger", e);
        }
    }

    public void logChatMessage(String text) {
        try {
            Files.writeString(Paths.get("logs/ai_conversation.log"), text + "\n", 
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to write to ai_conversation.log", e);
        }
    }

    private void initializeModel() {
        int threads = capability.getIdealThreadCount();
        ModelParameters params = new ModelParameters()
                .setModel(modelFile.toString())
                .setCtxSize(4096) // Increased to give room for long protocols + analysis
                .setThreads(threads)
                .setThreadsBatch(threads)
                .setGpuLayers(-1);

        logger.info("================ AI ENGINE DASHBOARD ================");
        logger.log(Level.INFO, () -> String.format("  - MODEL:      %s", modelFile.getFileName()));
        logger.log(Level.INFO, () -> String.format("  - PROFILE:    %s", capability.getRecommendedProfile()));
        logger.log(Level.INFO, () -> String.format("  - CPU CORES:  %d", capability.getAvailableProcessors()));
        logger.log(Level.INFO, () -> String.format("  - THREADS:    %d", threads));
        logger.log(Level.INFO, () -> String.format("  - BATCH:      %d", threads));
        logger.info("  - GPU:        [OFF] (CPU Optimized)");
        
        // TEMPORARY SILENCE: Redirect stderr to hide native model loading info ("trash")
        @SuppressWarnings("squid:S106") // Use System.err explicitly to redirect it
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new OutputStream() { 
            @Override
            public void write(int b) {
                // Silently drop all bytes during native model initialization
            } 
        }));
        
        try {
            this.model = new LlamaModel(params);
            System.setErr(originalErr);
            logger.info("  STATUS: READY AND OPTIMIZED");
            logger.info(DASHBOARD_BORDER);
        } catch (Exception e) {
            System.setErr(originalErr);
            throw new AIEngineException("CRITICAL: Engine failed to load native library", e);
        }
    }

    /**
     * Generates a response using a streaming flow.
     * The listener is notified as tokens arrive and when the analysis header is complete.
     */
    public void generateResponse(List<ChatMessage> history, String context, String targetLanguage, com.soteria.core.model.UserData profile, InferenceListener listener) {
        String profileContext = (profile != null) 
            ? String.format("USER DATA: Name: %s | Medical Info: %s", 
                profile.fullName(), profile.medicalInfo())
            : "No user profile data available.";

        String systemInstruction = buildSystemInstruction(targetLanguage, profileContext, context);
        String prompt = buildGemmaPrompt(systemInstruction, history);
        
        logChatMessage(DASHBOARD_BORDER);
        logChatMessage("--- STREAMING PROMPT ---");
        logChatMessage(prompt);
        logChatMessage("----------------------------------------------------");

        InferenceParameters infer = createInferenceParameters(prompt);

        StringBuilder headerBuffer = new StringBuilder();
        StringBuilder textBuffer = new StringBuilder();
        boolean[] headerDone = {false};

        try {
            for (LlamaOutput output : model.generate(infer)) {
                String token = output.toString();
                if (!headerDone[0]) {
                    headerDone[0] = handleHeaderToken(token, headerBuffer, textBuffer, listener);
                } else {
                    handleTextToken(token, textBuffer, listener);
                }
            }
            
            listener.onComplete(textBuffer.toString().trim());
            logInferenceFinished(headerBuffer, textBuffer);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Streaming inference failed", e);
            listener.onError(e);
        }
    }

    private String buildSystemInstruction(String targetLanguage, String profileContext, String context) {
        return String.format(
                "## SYSTEM_ROLE: SOTERIA_ORCHESTRATOR_V3%n" +
                "You are the central routing engine for SoterIA. Your core objective is to map human natural language to precise emergency states using the provided manifest.%n%n" +
                "### CORE_PROTOCOL%n" +
                "1. **IDENTIFY**: Analyze the user's situation. Distinguish between greetings, information requests, and real emergencies.%n" +
                "2. **MAP**: Compare the situation against the IDs in the PROTOCOL_MANIFEST below.%n" +
                "3. **EMIT**: Every response must be prefixed with a structural ANALYSIS header.%n" +
                "4. **ASSIST**: After the header, provide a single, high-empathy sentence in %s. **CRITICAL**: If an emergency is detected, you MUST naturally translate and integrate the instruction from `FIRST_STEP_BASE` (from the manifest) into your response.%n%n" +
                "### HEADER_SCHEMA%n" +
                "You must strictly follow this sequence:%n" +
                "1. `[ANALYSIS] ID: {PROTOCOL_ID} | STATUS: {STATE} %s`%n" +
                "2. Your empathetic response (one sentence translating and integrating the first step if active).%n%n" +
                "- **PROTOCOL_ID**: The unique ID from the manifest. Use 'N/A' for non-emergencies.%n" +
                "- **STATE**: TRIAGE (idle/greeting), ACTIVE (emergency), or RESOLVED.%n%n" +
                "### CONTEXTUAL_DATA%n" +
                "**USER_PROFILE**: %s%n" +
                "**PROTOCOL_MANIFEST**:%n%s%n\n" +
                "### CRITICAL_LOGIC%n" +
                "- Output the header, THEN the %s tag, THEN your response text.%n" +
                "- If `STATUS: ACTIVE`, you MUST translate and mention the `FIRST_STEP_BASE` text in your response in a natural way.%n" +
                "- For greetings (hola, hi, etc), always use ID: N/A and STATUS: TRIAGE.%n" +
                "- Precision is life.",
                targetLanguage, HEADER_END_TAG, profileContext, context, HEADER_END_TAG
        );
    }

    private InferenceParameters createInferenceParameters(String prompt) {
        return new InferenceParameters(prompt)
                .setTemperature(0.1f)
                .setTopP(0.9f)
                .setNPredict(400)
                .setMiroStat(MiroStat.V2)
                .setStopStrings("<end_of_turn>");
    }

    private boolean handleHeaderToken(String token, StringBuilder headerBuffer, StringBuilder textBuffer, InferenceListener listener) {
        headerBuffer.append(token);
        String currentHeader = headerBuffer.toString();

        if (currentHeader.contains(HEADER_END_TAG)) {
            String[] parts = currentHeader.split("\\[END\\]", 2);
            parseAndNotifyAnalysis(parts[0] + HEADER_END_TAG, listener);
            
            if (parts.length > 1 && !parts[1].isEmpty()) {
                String residue = parts[1];
                textBuffer.append(residue);
                listener.onToken(residue);
            }
            return true;
        } 
        
        if (currentHeader.length() > 100 || token.contains("\n\n")) {
            parseAndNotifyAnalysis(currentHeader, listener);
            return true;
        }
        
        return false;
    }

    private void handleTextToken(String token, StringBuilder textBuffer, InferenceListener listener) {
        if (token.contains(HEADER_END_TAG)) return;
        textBuffer.append(token);
        listener.onToken(token);
    }

    private void logInferenceFinished(StringBuilder headerBuffer, StringBuilder textBuffer) {
        logChatMessage("--- INFERENCE FINISHED ---");
        logChatMessage("Header: " + headerBuffer.toString().trim());
        logChatMessage("Text:   " + textBuffer.toString().trim());
        logChatMessage(DASHBOARD_BORDER);
    }

    private void parseAndNotifyAnalysis(String header, InferenceListener listener) {
        try {
            String id = "N/A";
            String status = "TRIAGE";
            
            if (header.contains("ID:")) {
                String[] parts = header.split("ID:")[1].split("\\|");
                id = parts[0].trim();
            }
            if (header.contains("STATUS:")) {
                String afterStatus = header.split("STATUS:")[1].trim();
                // Take only the first word (TRIAGE, ACTIVE, RESOLVED)
                status = afterStatus.split("[\\s\\[\\|]")[0].trim();
            }
            
            listener.onAnalysisComplete(id, status);
        } catch (Exception e) {
            logger.warning("Failed to parse analysis header: " + header);
            listener.onAnalysisComplete("N/A", "TRIAGE");
        }
    }







    /**
     * Builds a Gemma 3 multi-turn prompt. Gemma has no separate "system" role,
     * so the system instruction is prepended to the first user turn. History
     * must alternate user/model and end with a user turn.
     */
    static String buildGemmaPrompt(String systemInstruction, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            sb.append("<start_of_turn>").append(msg.role()).append('\n');
            if (i == 0 && "user".equals(msg.role())) {
                sb.append(systemInstruction).append("\n\n");
            }
            sb.append(msg.content()).append("<end_of_turn>\n");
        }
        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    @Override
    public void close() {
        try {
            if (model != null) model.close();
        } catch (Throwable t) {
            logger.log(Level.FINE, "Silent failure during native memory cleanup (expected on some systems)", t);
        }
    }
}
