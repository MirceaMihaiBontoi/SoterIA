package com.soteria.infrastructure.intelligence;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.args.MiroStat;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local LLM inference service powered by llama.cpp (GGUF format).
 * Offline-first, CPU + GPU (Metal/Vulkan) depending on the native build.
 */
public class LocalBrainService implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(LocalBrainService.class.getName());

    private final Path modelFile;
    private LlamaModel model;

    public LocalBrainService(Path modelFile) {
        this.modelFile = modelFile;
        initializeModel();
    }

    private void initializeModel() {
        ModelParameters params = new ModelParameters()
                .setModel(modelFile.toString())
                .setCtxSize(2048)
                .setGpuLayers(-1);

        this.model = new LlamaModel(params);
        logger.info("LocalBrainService ready with GGUF: " + modelFile.getFileName());
    }

    /**
     * Generates a response using an English-core prompt that instructs the model
     * to reply in the user's target language.
     */
    public String generateResponse(List<ChatMessage> history, String context, String targetLanguage) {
        String systemInstruction = String.format(
                "You are SoterIA, a calm first-response medical assistant. Triage before acting:%n" +
                        "- If the user greets you or is making small talk, respond briefly and naturally, then ask what is happening.%n" +
                        "- If the user's message is ambiguous, ask ONE short clarifying question. Do NOT assume an emergency.%n" +
                        "- Only give life-saving protocol steps when the user has clearly described a medical problem.%n" +
                        "- Never invent symptoms. Never push a protocol the user did not describe.%n" +
                        "- Do NOT greet the user on every turn. Greet only on the very first message.%n" +
                        "Always answer in %s. Keep answers short (1-3 sentences) unless giving critical steps.%n" +
                        "Reference knowledge (may or may not apply — use judgment): %s",
                targetLanguage, context
        );

        String prompt = buildGemmaPrompt(systemInstruction, history);

        InferenceParameters infer = new InferenceParameters(prompt)
                .setTemperature(0.3f)
                .setTopP(0.9f)
                .setNPredict(256)
                .setMiroStat(MiroStat.V2)
                .setStopStrings("<end_of_turn>");

        try {
            return model.complete(infer).trim();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Local inference failed", e);
            return targetLanguage.equalsIgnoreCase("Spanish")
                    ? "Error en el motor de IA local. Verifique los protocolos en el Dashboard."
                    : "Local AI engine error. Check protocols in the Dashboard.";
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
        if (model != null) {
            model.close();
        }
    }
}
