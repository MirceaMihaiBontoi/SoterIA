package com.soteria.infrastructure.intelligence.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles file-based logging for LLM requests, responses, and conversation history.
 */
public class LLMLogger {
    private static final Logger logger = Logger.getLogger(LLMLogger.class.getName());
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String DASHBOARD_BORDER = "====================================================";

    public void setup() {
        try {
            Path logDir = Paths.get("logs");
            Path rawDir = Paths.get("logs/raw_llm");

            if (!Files.exists(logDir)) Files.createDirectories(logDir);
            if (!Files.exists(rawDir)) Files.createDirectories(rawDir);

            // Fresh start for logs
            initLogFile("logs/ai_conversation.log", "--- SoterIA AI Conversation Log (Autocleaned) ---\n");
            initLogFile("logs/raw_llm/llm_input.log", "--- SoterIA Raw LLM Input (Autocleaned) ---\n");
            initLogFile("logs/raw_llm/llm_output.log", "--- SoterIA Raw LLM Output (Autocleaned) ---\n");

        } catch (IOException e) {
            logger.log(Level.WARNING, e, () -> "Failed to initialize LLM logging system");
        }
    }

    private void initLogFile(String path, String header) throws IOException {
        Files.writeString(Paths.get(path), header,
                java.nio.charset.StandardCharsets.UTF_8, 
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void logChatMessage(String text) {
        appendToFile("logs/ai_conversation.log", text + "\n");
    }

    public void logRaw(String fileName, String content) {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String entry = String.format(
                "%s | [ENTRY]%n----------------------------------------------------%n%s%n----------------------------------------------------%n%n",
                time, content);
        appendToFile("logs/raw_llm/" + fileName, entry);
    }

    public void logInferenceRequest(String language, int historySize, String context) {
        String log = String.format("[%s] Lang: %s | Context: %s | Hist: %d turns%n",
                LocalDateTime.now().format(TIME_FORMATTER), language, context, historySize);
        logRaw("inference_requests.log", log);
    }

    public void logInferenceFinished(String header, String text, long ttft) {
        logChatMessage("--- INFERENCE COMPLETED ---");
        if (ttft > 0) {
            logChatMessage("  - Latency (TTFT): " + ttft + " ms");
        }
        logChatMessage("  " + header.trim());
        logChatMessage("  " + text.trim().replace("\n", " "));
        logChatMessage(DASHBOARD_BORDER);
    }

    private void appendToFile(String path, String content) {
        try {
            Files.writeString(Paths.get(path), content,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, e, () -> "Failed to write to log file: " + path);
        }
    }
}
