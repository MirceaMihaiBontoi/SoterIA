package com.soteria.infrastructure.intelligence.tts;

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
 * Handles file-based logging for TTS operations, replacing terminal output.
 * Log files are stored in logs/voice/
 */
public class TTSLogger {
    private static final Logger logger = Logger.getLogger(TTSLogger.class.getName());
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BORDER = "====================================================";

    private static final String LOG_PATH = "logs/voice/tts.log";

    public void setup() {
        try {
            Path logDir = Paths.get("logs", "voice");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }

            // Initialize or clear main tts log
            initLogFile(LOG_PATH, "--- SoterIA TTS System Log (Autocleaned) ---\n");
            
        } catch (IOException e) {
            logger.log(Level.WARNING, e, () -> "Failed to initialize TTS logging system");
        }
    }

    private void initLogFile(String path, String header) throws IOException {
        Files.writeString(Paths.get(path), header,
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void info(String message) {
        log("INFO", message);
    }

    public void warn(String message) {
        log("WARN", message);
    }

    public void error(String message, Throwable t) {
        log("ERROR", message + (t != null ? " | " + t.getMessage() : ""));
    }

    public void logSynthesis(String language, String text, long audioDurationMs, float rate) {
        String entry = String.format("[%s] Synthesis: [%s] -> %s | Duration: %dms | Rate: %.2f",
                language, text, text.length() > 20 ? text.substring(0, 17) + "..." : text, audioDurationMs, rate);
        info(entry);
    }

    public void logInitialization(Path modelPath, int threads) {
        appendToFile(LOG_PATH, BORDER + "\n");
        info("  TTS ENGINE INITIALIZED");
        info("  - MODEL:      " + modelPath.getFileName());
        info("  - THREADS:    " + threads);
        info("  - STATUS:     READY");
        appendToFile(LOG_PATH, BORDER + "\n");
    }

    private void log(String level, String message) {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String entry = String.format("%s [%s] %s%n", time, level, message);
        appendToFile(LOG_PATH, entry);
    }

    private void appendToFile(String path, String content) {
        try {
            Files.writeString(Paths.get(path), content,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, e, () -> "Failed to write to TTS log: " + path);
        }
    }
}
