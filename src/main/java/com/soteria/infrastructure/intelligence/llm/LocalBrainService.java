package com.soteria.infrastructure.intelligence.llm;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.exception.AIEngineException;
import com.soteria.core.port.Brain;
import com.soteria.core.port.InferenceListener;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;

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
public class LocalBrainService implements AutoCloseable, Brain {
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
    public void chat(ChatSession session, String context, com.soteria.core.model.UserData profile, String language,
            BrainCallback callback) {
        chat(session.getMessages(), context, profile, language, callback);
    }

    public void chat(List<ChatMessage> history, String context, com.soteria.core.model.UserData profile,
            String language,
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
        String prompt = preparePrompt(history, context, targetLanguage, profile);

        logInferenceRequest(targetLanguage, history, context);
        logRaw("llm_input.log", prompt);

        try {
            InferenceParameters infer = createInferenceParameters(prompt);
            StringBuilder fullOutput = new StringBuilder();

            boolean rejected = runInferenceLoop(infer, listener, fullOutput);

            String finalResult = fullOutput.toString().trim();
            logRaw("llm_output.log", finalResult);

            finalizeResponse(finalResult, rejected, listener);
            logInferenceFinished("MODE: FREEFORM", finalResult, 0);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Streaming inference failed", e);
            listener.onError(e);
        }
    }

    private String preparePrompt(List<ChatMessage> history, String context, String targetLanguage,
            com.soteria.core.model.UserData profile) {
        String profileContext = (profile != null)
                ? String.format("USER DATA: Name: %s | Medical Info: %s", profile.fullName(), profile.medicalInfo())
                : "No user profile data available.";

        String staticSystem = buildStaticInstructions(targetLanguage);
        String dynamicContext = buildDynamicContext(profileContext, context);
        return buildGemmaPrompt(staticSystem, dynamicContext, history);
    }

    private boolean runInferenceLoop(InferenceParameters infer, InferenceListener listener, StringBuilder fullOutput) {
        StringBuilder rejectBuffer = new StringBuilder();
        boolean[] isReject = { false };
        boolean[] isFirstTokens = { true };

        for (LlamaOutput output : model.generate(infer)) {
            String token = output.toString();
            fullOutput.append(token);

            if (isFirstTokens[0]) {
                if (detectRejection(token, rejectBuffer, isReject, isFirstTokens, listener)) {
                    return true;
                }
            } else if (!isReject[0]) {
                listener.onToken(token);
            }
        }
        return isReject[0];
    }

    private boolean detectRejection(String token, StringBuilder rejectBuffer, boolean[] isReject,
            boolean[] isFirstTokens, InferenceListener listener) {
        rejectBuffer.append(token);
        String current = rejectBuffer.toString();

        if (current.toUpperCase().startsWith("REJECT:")) {
            isReject[0] = true;
            return current.contains("\n"); // Break loop if we have the full rejection line
        }

        if (current.length() > 15 || current.contains("\n")) {
            isFirstTokens[0] = false;
            listener.onToken(current);
        }
        return false;
    }

    private void finalizeResponse(String finalResult, boolean rejected, InferenceListener listener) {
        if (rejected) {
            int rejectIdx = finalResult.toUpperCase().indexOf("REJECT:");
            String reason = finalResult.substring(rejectIdx + 7).split("\n")[0].trim();
            logger.log(Level.INFO, "Inference REJECTED by brain. Reason: {0}", reason);
            listener.onAnalysisComplete("REJECT", reason);
            listener.onComplete("");
        } else {
            listener.onComplete(finalResult);
        }
    }

    private String buildStaticInstructions(String targetLanguage) {
        String staticPrompt = """
                ## ROLE: EMERGENCY_DISPATCHER
                You are a calm and supportive human dispatcher helping someone in a crisis.
                
                ### CRITICAL INSTRUCTIONS
                1. **LANGUAGE**: Always respond in [TARGET_LANG]. Even if the instructions are in English, your response MUST be in [TARGET_LANG].
                2. **STAY IN CHARACTER**: Talk like a real person on a phone call. Be supportive, empathetic, and direct.
                3. **USE THE PROTOCOL**: Use the provided protocol as your technical knowledge base. Don't mention "steps" or "protocols". Just use the information to give the best advice for the situation.
                4. **BREVITY**: Keep your response to 1 or 2 natural sentences. Focus on the immediate action the user should take.
                """;
        return staticPrompt.replace("[TARGET_LANG]", targetLanguage);
    }

    private String buildDynamicContext(String profileContext, String context) {
        String template = """
                ### SITUATIONAL_DATA
                **USER_BACKGROUND_PROFILE (DO NOT MENTION UNLESS RELEVANT)**: [PROFILE]
                **PROTOCOL**:
                [MANIFEST]
                """;
        return template.replace("[PROFILE]", profileContext).replace("[MANIFEST]", context);
    }

    private InferenceParameters createInferenceParameters(String prompt) {
        return new InferenceParameters(prompt)
                .setTemperature(0.4f)
                .setTopP(0.9f)
                .setRepeatPenalty(1.2f)
                .setNPredict(128)
                .setStopStrings("<end_of_turn>");
    }

    private void logInferenceFinished(String header, String text, long ttft) {
        logChatMessage("--- INFERENCE COMPLETED ---");
        if (ttft > 0) {
            logChatMessage("  - Latency (TTFT): " + ttft + " ms");
        }
        logChatMessage("  " + header.trim());
        logChatMessage("  " + text.trim().replace("\n", " "));
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

            if (i == lastIndex && "user".equals(msg.role())) {
                // Simplified reminder to focus on language and natural tone
                sb.append(msg.content())
                  .append("\n\n(Respond in [TARGET_LANG]. 1-2 natural sentences.)")
                  .append("<end_of_turn>\n");
            } else {
                sb.append(msg.content()).append("<end_of_turn>\n");
            }
        }

        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    private void logInferenceRequest(String language, List<ChatMessage> history, String context) {
        String log = String.format("[%s] Lang: %s | Context: %s | Hist: %d turns%n",
                LocalDateTime.now().format(TIME_FORMATTER), language, context, history.size());
        logRaw("inference_requests.log", log);
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
