package com.soteria.infrastructure.intelligence.llm;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.exception.AIEngineException;
import com.soteria.core.port.Brain;
import com.soteria.core.port.InferenceListener;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local LLM inference service powered by llama.cpp.
 * Orchestrates model lifecycle and execution, delegating formatting and logging.
 */
public class LocalBrainService implements AutoCloseable, Brain {
    private static final Logger logger = Logger.getLogger(LocalBrainService.class.getName());

    private final Path modelFile;
    private final SystemCapability capability;
    private final LLMLogger brainLogger;
    private final GemmaPromptBuilder promptBuilder;
    
    private LlamaModel model;
    private volatile boolean isCancelled = false;

    public LocalBrainService(Path modelFile, SystemCapability capability) {
        this.modelFile = modelFile;
        this.capability = capability;
        this.brainLogger = new LLMLogger();
        this.promptBuilder = new GemmaPromptBuilder();
        
        this.brainLogger.setup();
        initializeModel();
    }

    @Override
    public void cancel() {
        this.isCancelled = true;
    }

    private void initializeModel() {
        int threads = capability.getIdealThreadCount();
        ModelParameters params = new ModelParameters()
                .setModel(modelFile.toString())
                .setCtxSize(4096)
                .setThreads(threads)
                .setThreadsBatch(threads)
                .setGpuLayers(-1);

        logger.info("================ AI ENGINE DASHBOARD ================");
        logger.log(Level.INFO, "  - MODEL:      {0}", modelFile.getFileName());
        logger.log(Level.INFO, "  - PROFILE:    {0}", capability.getRecommendedProfile());
        logger.log(Level.INFO, "  - THREADS:    {0}", threads);
        logger.info("  - GPU:        [OFF] (CPU Optimized)");

        // Redirect stderr to hide native model loading noise
        @SuppressWarnings("squid:S106")
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override public void write(int b) { /* silence */ }
        }));

        try {
            this.model = new LlamaModel(params);
            System.setErr(originalErr);
            logger.info("  STATUS: READY AND OPTIMIZED");
            logger.info("====================================================");
        } catch (Exception e) {
            System.setErr(originalErr);
            throw new AIEngineException("CRITICAL: Engine failed to load native library", e);
        }
    }

    @Override
    public void chat(List<ChatMessage> history, String context, com.soteria.core.model.UserData profile,
            String language, BrainCallback callback) {
        generateResponse(history, context, language, profile, new InferenceListener() {
            @Override
            public void onToken(String token) {
                callback.onPartialResponse(token);
            }

            @Override
            public void onAnalysisComplete(String protocolId, String status) {
                callback.onStatusUpdate(protocolId, status);
            }

            @Override
            public void onComplete(String text) {
                parseAndExecuteCommands(text, callback);
                callback.onFinalResponse(text);
            }

            @Override
            public void onError(Throwable t) {
                callback.onStatusUpdate(null, "ERROR: " + t.getMessage());
            }
        });
    }

    private void parseAndExecuteCommands(String text, BrainCallback callback) {
        if (text.toUpperCase().contains("STEP:")) {
            try {
                String[] parts = text.toUpperCase().split("STEP:");
                if (parts.length > 1) {
                    String step = parts[1].split("[\\s|]")[0].trim();
                    if (!step.isEmpty()) callback.onCommand("STEP", step);
                }
            } catch (Exception e) {
                logger.warning("Failed to parse STEP command: " + e.getMessage());
            }
        }
    }

    public void generateResponse(List<ChatMessage> history, String context, String targetLanguage,
            com.soteria.core.model.UserData profile, InferenceListener listener) {
        this.isCancelled = false;
        String prompt = promptBuilder.preparePrompt(history, context, targetLanguage, profile);

        brainLogger.logInferenceRequest(targetLanguage, history.size(), context);
        brainLogger.logRaw("llm_input.log", prompt);

        try {
            InferenceParameters infer = createInferenceParameters(prompt);
            StringBuilder fullOutput = new StringBuilder();

            boolean rejected = runInferenceLoop(infer, listener, fullOutput);

            String finalResult = fullOutput.toString().trim();
            brainLogger.logRaw("llm_output.log", finalResult);

            finalizeResponse(finalResult, rejected, listener);
            brainLogger.logInferenceFinished("MODE: FREEFORM", finalResult, 0);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Streaming inference failed", e);
            listener.onError(e);
        }
    }

    private boolean runInferenceLoop(InferenceParameters infer, InferenceListener listener, StringBuilder fullOutput) {
        StringBuilder rejectBuffer = new StringBuilder();
        boolean[] isReject = { false };
        boolean[] isFirstTokens = { true };

        for (LlamaOutput output : model.generate(infer)) {
            if (isCancelled) break;
            
            String token = output.toString();
            fullOutput.append(token);

            if (isFirstTokens[0]) {
                if (detectRejection(token, rejectBuffer, isReject, isFirstTokens, listener)) return true;
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
            return current.contains("\n");
        }

        if (current.length() > 15 || current.contains("\n")) {
            isFirstTokens[0] = false;
            listener.onToken(current);
        }
        return false;
    }

    private void finalizeResponse(String finalResult, boolean rejected, InferenceListener listener) {
        if (isCancelled) {
            logger.info("Skipping inference finalize callbacks (cancelled mid-stream).");
            return;
        }
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

    private InferenceParameters createInferenceParameters(String prompt) {
        return new InferenceParameters(prompt)
                .setTemperature(0.4f)
                .setTopP(0.9f)
                .setRepeatPenalty(1.2f)
                .setNPredict(128)
                .setStopStrings(GemmaPromptBuilder.GEMMA_ASSISTANT_STOP_SEQUENCES);
    }

    @Override
    public void close() {
        try {
            if (model != null) model.close();
        } catch (Exception e) {
            logger.log(Level.FINE, "Silent failure during native memory cleanup", e);
        }
    }
}
