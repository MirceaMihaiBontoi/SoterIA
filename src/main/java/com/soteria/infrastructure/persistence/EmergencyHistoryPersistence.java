package com.soteria.infrastructure.persistence;

import com.soteria.core.interfaces.HistoryLogger;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserFeedback;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class in charge of registering and storing the history of emergencies and user feedback.
 * Also implements HistoryLogger for general system logging.
 */
public class EmergencyHistoryPersistence implements HistoryLogger {
    private static final Logger log = Logger.getLogger(EmergencyHistoryPersistence.class.getName());
    
    private static final String HISTORY_FILE = "logs/emergency_history.log";
    private static final String FEEDBACK_FILE = "logs/user_feedback.log";
    private static final String SYSTEM_LOG_FILE = "logs/system.log";
    private static final String LOGS_DIR = "logs";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public EmergencyHistoryPersistence() {
        createLogsDirectoryIfNotExists();
    }

    private static void createLogsDirectoryIfNotExists() {
        File logsDir = new File(LOGS_DIR);
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
    }

    /**
     * Logs an emergency in the history.
     */
    public String logEmergency(EmergencyEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Emergency event cannot be null");
        }
        
        String emergencyId = UUID.randomUUID().toString();
        String logEntry = String.format(
            "[%s] ID: %s | Type: %s | Location: %s | Severity: %s%n",
            LocalDateTime.now().format(TIMESTAMP_FORMAT),
            emergencyId,
            event.emergencyType(),
            event.location(),
            event.severityLevel()
        );

        appendToFile(HISTORY_FILE, logEntry);
        return emergencyId;
    }

    /**
     * Logs user feedback to the feedback file.
     */
    public void logFeedback(UserFeedback feedback) {
        if (feedback == null) return;
        
        String logEntry = String.format(
            "[%s] Emergency ID: %s | Rating: %d/5 | Comments: %s%n",
            feedback.feedbackTime().format(TIMESTAMP_FORMAT),
            feedback.emergencyId(),
            feedback.satisfactionRating(),
            feedback.comments()
        );

        appendToFile(FEEDBACK_FILE, logEntry);
    }

    // --- HistoryLogger Implementation ---

    @Override
    public void logInfo(String message) {
        appendToFile(SYSTEM_LOG_FILE, "[INFO] [" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + message + "\n");
    }

    @Override
    public void logWarning(String message) {
        appendToFile(SYSTEM_LOG_FILE, "[WARN] [" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + message + "\n");
    }

    @Override
    public void logError(String message, Exception e) {
        String errorMsg = e != null ? e.getMessage() : "No exception provided";
        appendToFile(SYSTEM_LOG_FILE, "[ERROR] [" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + message + " : " + errorMsg + "\n");
    }

    @Override
    public String getLogLocation() {
        return LOGS_DIR;
    }

    private void appendToFile(String fileName, String content) {
        try (FileWriter writer = new FileWriter(fileName, true)) {
            writer.write(content);
        } catch (IOException e) {
            log.log(Level.SEVERE, "❌ Error writing to log file: {0}", e.getMessage());
        }
    }
}
