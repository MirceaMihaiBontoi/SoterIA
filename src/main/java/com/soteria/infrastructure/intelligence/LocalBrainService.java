package com.soteria.infrastructure.intelligence;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.exception.AIEngineException;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.args.MiroStat;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

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
            Path rawDir = Paths.get("logs/raw_llm");

            if (!Files.exists(logDir))
                Files.createDirectories(logDir);
            if (!Files.exists(rawDir))
                Files.createDirectories(rawDir);

            // Ensure a fresh start on every app launch (autolimpia)
            Files.writeString(Paths.get("logs/ai_conversation.log"),
                    "--- SoterIA AI Conversation Log (Autocleaned) ---\n",
                    java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            Files.writeString(Paths.get("logs/raw_llm/llm_input.log"), "--- SoterIA Raw LLM Input (Autocleaned) ---\n",
                    java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            Files.writeString(Paths.get("logs/raw_llm/llm_output.log"),
                    "--- SoterIA Raw LLM Output (Autocleaned) ---\n",
                    java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to initialize logging system", e);
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

    private void logRaw(String fileName, String content) {
        try {
            String time = LocalDateTime.now().format(TIME_FORMATTER);
            String entry = String.format(
                    "%s | [ENTRY]%n----------------------------------------------------%n%s%n----------------------------------------------------%n%n",
                    time, content);
            Files.writeString(Paths.get("logs/raw_llm", fileName), entry,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, e, () -> "Failed to write to raw log: " + fileName);
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

        // TEMPORARY SILENCE: Redirect stderr to hide native model loading info
        // ("trash")
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
     * The listener is notified as tokens arrive and when the analysis header is
     * complete.
     */
    public interface BrainCallback {
        void onPartialResponse(String text);

        void onFinalResponse(String text);

        void onStatusUpdate(String protocolId, String status);

        void onCommand(String type, String value);
    }

    public void chat(ChatSession session, String context, com.soteria.core.model.UserData profile, String language,
            BrainCallback callback) {
        chat(session.getMessages(), context, profile, language, callback);
    }

    public void chat(List<ChatMessage> history, String context, com.soteria.core.model.UserData profile, String language,
            BrainCallback callback) {
        generateResponse(history, context, language, profile, new InferenceListener() {
            @Override
            public void onToken(String token) {
                // Tokens are usually full chunks in this build
                callback.onPartialResponse(token);
            }

            @Override
            public void onAnalysisComplete(String protocolId, String status) {
                callback.onStatusUpdate(protocolId, status);
            }

            @Override
            public void onComplete(String text) {
                // Check for commands in the text - ONLY STEP is allowed
                if (text.toUpperCase().contains("STEP:")) {
                    try {
                        String[] parts = text.toUpperCase().split("STEP:");
                        if (parts.length > 1) {
                            String step = parts[1].split("[\\s|]")[0].trim();
                            if (!step.isEmpty()) {
                                callback.onCommand("STEP", step);
                            }
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to parse STEP command: " + e.getMessage());
                    }
                }
                callback.onFinalResponse(text);
            }

            @Override
            public void onError(Throwable t) {
                callback.onStatusUpdate(null, "ERROR: " + t.getMessage());
            }
        });
    }

    /**
     * Generates a response using a streaming flow.
     */
    public void generateResponse(List<ChatMessage> history, String context, String targetLanguage,
            com.soteria.core.model.UserData profile, InferenceListener listener) {
        String profileContext = (profile != null)
                ? String.format("USER DATA: Name: %s | Medical Info: %s",
                        profile.fullName(), profile.medicalInfo())
                : "No user profile data available.";

        String staticSystem = buildStaticInstructions(targetLanguage);
        String dynamicContext = buildDynamicContext(profileContext, context);
        String prompt = buildGemmaPrompt(staticSystem, dynamicContext, history);

        // Log a cleaner version of the inference request
        logChatMessage(DASHBOARD_BORDER);
        logChatMessage("--- INFERENCE REQUEST ---");
        logChatMessage("  - Language: " + targetLanguage);
        logChatMessage("  - History:  " + (history.size() - 1) + " turns");
        logChatMessage("  - User:     " + history.get(history.size() - 1).content());

        // Truncate context for logs to keep them readable
        String contextSnippet = context.length() > 200 ? context.substring(0, 200) + "..." : context;
        logChatMessage("  - Context:  " + contextSnippet.replace("\n", " "));
        logChatMessage("----------------------------------------------------");

        logRaw("llm_input.log", prompt);
        StringBuilder fullOutput = new StringBuilder();

        InferenceParameters infer = createInferenceParameters(prompt);

        StringBuilder headerBuffer = new StringBuilder();
        StringBuilder textBuffer = new StringBuilder();
        boolean[] headerDone = { false };

        long startTime = System.currentTimeMillis();
        long ttft = -1;

        try {
            for (LlamaOutput output : model.generate(infer)) {
                if (ttft == -1) {
                    ttft = System.currentTimeMillis() - startTime;
                }
                String token = output.toString();
                listener.onToken(token); // Send everything to UI for filtering

                if (!headerDone[0]) {
                    headerDone[0] = handleHeaderToken(token, headerBuffer, textBuffer, listener);
                } else {
                    handleTextToken(token, textBuffer);
                }
                fullOutput.append(token);
            }

            logRaw("llm_output.log", fullOutput.toString());
            listener.onComplete(textBuffer.toString().trim());
            logInferenceFinished(headerBuffer, textBuffer, ttft);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Streaming inference failed", e);
            listener.onError(e);
        }
    }

    private String buildStaticInstructions(String targetLanguage) {
        String prompt = """
                ## ROLE: SOTERIA_EMERGENCY_DISPATCHER
                You are Soteria, a professional emergency dispatcher. You provide authoritative guidance based on the urgency of the user's situation.

                ### CORE_PRINCIPLES
                1. **Prioritize Life**: If a threat is detected, give life-saving orders immediately.
                2. **Command and Control**: You are the expert. Do not ask for permission; provide clear directions.
                3. **Voice-optimized**: Keep responses punchy and direct. No filler or technical jargon.

                ### DYNAMIC_RESPONSE_FRAMEWORK
                Adapt your style based on the detected **URGENCY_LEVEL**:

                - **LEVEL_3 (IMMEDIATE_THREAT)**:
                  - TONE: Absolute authority.
                  - ACTION: Start with 1-2 words of composure (e.g. "Escúchame bien") and then give 1-2 mandatory safety instructions using imperative verbs.
                  - FORBIDDEN: Do not ask "How can I help?".

                - **LEVEL_2 (UNCERTAIN/EVOLVING)**:
                  - TONE: Investigative and calm.
                  - ACTION: Ask 1 specific question to identify the exact nature of the threat or verify the user's safety.

                - **LEVEL_1 (CASUAL/GREETING)**:
                  - TONE: Professional and friendly.
                  - ACTION: Respond naturally and briefly. Set STATUS=INACTIVE.

                ### MANDATORY_FORMAT
                ANALISIS: ID={PROTOCOL_ID} [Optional STEP:N if next action is clear]
                RESPONSE: {Your direct, spoken response to the user in [TARGET_LANG]}

                ### EMERGENCY_CONTEXT
                - Use the provided SITUATIONAL_DATA to personalize your guidance, but never reference internal system logic (like "protocol ID").
                - If no protocol matches but a threat exists, use your internal expertise to provide standard safety guidance.
                """;
        return prompt.replace("[TARGET_LANG]", targetLanguage);
    }

    private String buildDynamicContext(String profileContext, String context) {
        String template = """
                ### SITUATIONAL_DATA
                **USER_BACKGROUND_PROFILE (DO NOT MENTION UNLESS RELEVANT)**: [PROFILE]
                **EMERGENCY_PROTOCOL_MANIFEST**:
                [MANIFEST]
                """;
        return template.replace("[PROFILE]", profileContext).replace("[MANIFEST]", context);
    }

    private InferenceParameters createInferenceParameters(String prompt) {
        return new InferenceParameters(prompt)
                .setTemperature(0.1f)
                .setTopP(0.9f)
                .setNPredict(800)
                .setMiroStat(MiroStat.V2)
                .setStopStrings("<end_of_turn>");
    }

    private static final String ANALYSIS_TAG = "ANALISIS:";
    private static final String RESPONSE_TAG = "RESPONSE:";

    private void parseAndNotifyAnalysis(String rawHeader, InferenceListener listener) {
        String cleanHeader = rawHeader.replace(ANALYSIS_TAG, "").replace(RESPONSE_TAG, "").trim();

        String protocolId = "N/A";
        String status = "INACTIVE";

        try {
            if (cleanHeader.contains("ID=")) {
                protocolId = cleanHeader.split("ID=")[1].split("\\|")[0].trim();
            }
            if (cleanHeader.contains("STATUS=")) {
                status = cleanHeader.split("STATUS=")[1].split("\\|")[0].trim();
            }
        } catch (Exception e) {
            logger.warning("Header parsing failed: " + e.getMessage());
        }

        listener.onAnalysisComplete(protocolId, status);
    }

    private boolean handleHeaderToken(String token, StringBuilder headerBuffer, StringBuilder textBuffer,
            InferenceListener listener) {
        headerBuffer.append(token);
        String currentHeader = headerBuffer.toString();

        if (currentHeader.contains(RESPONSE_TAG)) {
            String[] parts = currentHeader.split(RESPONSE_TAG, 2);
            parseAndNotifyAnalysis(parts[0], listener);

            if (parts.length > 1 && !parts[1].isEmpty()) {
                textBuffer.append(parts[1]);
            }
            return true;
        }

        if (currentHeader.length() > 200) {
            parseAndNotifyAnalysis(currentHeader, listener);
            return true;
        }

        return false;
    }

    private void handleTextToken(String token, StringBuilder textBuffer) {
        textBuffer.append(token);
    }

    private void logInferenceFinished(StringBuilder headerBuffer, StringBuilder textBuffer, long ttft) {
        logChatMessage("--- INFERENCE COMPLETED ---");
        if (ttft > 0) {
            logChatMessage("  - Latency (TTFT): " + ttft + " ms");
        }
        logChatMessage("  " + headerBuffer.toString().trim());
        logChatMessage("  " + textBuffer.toString().trim().replace("\n", " "));
        logChatMessage(DASHBOARD_BORDER);
    }

    /**
     * Builds a Gemma 3 multi-turn prompt. Gemma has no separate "system" role,
     * so the system instruction is prepended to the first user turn. History
     * must alternate user/model and end with a user turn.
     */
    static String buildGemmaPrompt(String staticSystem, String dynamicContext, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        int lastIndex = history.size() - 1;

        for (int i = 0; i <= lastIndex; i++) {
            ChatMessage msg = history.get(i);
            sb.append("<start_of_turn>").append(msg.role()).append('\n');

            if (i == 0 && "user".equals(msg.role())) {
                // Static instructions at the very beginning of the history
                sb.append("## SYSTEM_INSTRUCTIONS\n")
                        .append(staticSystem)
                        .append("\n\n");
            }

            if (i == lastIndex && "user".equals(msg.role())) {
                // Situational context only in the last user turn (optimizes prefix caching)
                sb.append("## SITUATIONAL_CONTEXT\n")
                        .append(dynamicContext)
                        .append("\n\n## USER_INPUT\n");
            } else if (i == 0 && "user".equals(msg.role())) {
                // Mark the first message specifically if it's not the current query
                sb.append("## FIRST_USER_MESSAGE\n");
            }

            sb.append(msg.content()).append("<end_of_turn>\n");
        }
        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    @Override
    public void close() {
        try {
            if (model != null)
                model.close();
        } catch (Exception e) {
            logger.log(Level.FINE, "Silent failure during native memory cleanup (expected on some systems)", e);
        }
    }
}
