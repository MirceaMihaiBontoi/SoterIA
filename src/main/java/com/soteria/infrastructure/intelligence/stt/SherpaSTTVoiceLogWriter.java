package com.soteria.infrastructure.intelligence.stt;

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
 * Append-only trace file for STT partial and final strings, under {@code logs/voice/stt.log}.
 * <p>
 * Uses the same {@link Logger} name as {@link SherpaSTTService} so filter configuration stays unified.
 * Failures during setup are warned; per-line write failures are logged at {@link Level#FINE} to avoid noise.
 * </p>
 */
final class SherpaSTTVoiceLogWriter {

    private static final Logger logger = Logger.getLogger(SherpaSTTService.class.getName());
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String VOICE_LOG_DIR = "logs/voice";
    private static final String STT_LOG_FILE = "stt.log";

    /**
     * Ensures the log directory exists and truncates the STT log file with a session header.
     */
    void setup() {
        try {
            Path dir = Paths.get(VOICE_LOG_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Files.writeString(dir.resolve(STT_LOG_FILE),
                    "--- SoterIA STT Raw Log (Started: " + LocalDateTime.now() + ") ---\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "STT: Failed to initialize voice logging", e);
        }
    }

    /**
     * Appends one timestamped line. Creates the file if missing.
     *
     * @param content formatted transcript line (caller may include PARTIAL/FINAL prefix)
     */
    void logVoice(String content) {
        try {
            Path path = Paths.get(VOICE_LOG_DIR, STT_LOG_FILE);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            Files.writeString(path, String.format("%s | %s%n", timestamp, content),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to write STT log", e);
        }
    }
}
