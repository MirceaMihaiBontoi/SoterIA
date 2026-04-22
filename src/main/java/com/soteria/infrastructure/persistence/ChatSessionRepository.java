package com.soteria.infrastructure.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.soteria.infrastructure.intelligence.ChatSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles JSON-based persistence for chat sessions.
 * Sessions are stored in the user's home directory.
 */
public class ChatSessionRepository {
    private static final Logger log = Logger.getLogger(ChatSessionRepository.class.getName());
    private static final String JSON_EXTENSION = ".json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path sessionsDir;

    public ChatSessionRepository() {
        this(Paths.get(System.getProperty("user.home"), ".soteria", "sessions"));
    }

    public ChatSessionRepository(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        ensureDirectory();
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            log.log(Level.SEVERE, e, () -> "Could not create sessions directory: " + sessionsDir);
        }
    }

    public List<ChatSession> listSessions() {
        List<ChatSession> sessions = new ArrayList<>();
        File dir = sessionsDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.endsWith(JSON_EXTENSION));

        if (files != null) {
            for (File file : files) {
                try {
                    sessions.add(MAPPER.readValue(file, ChatSession.class));
                } catch (IOException e) {
                    log.log(Level.WARNING, e, () -> "Failed to load session from: " + file.getName());
                }
            }
        }
        
        // Sort by timestamp descending (newest first)
        sessions.sort(Comparator.comparingLong(ChatSession::getTimestamp).reversed());
        return sessions;
    }

    public void save(ChatSession session) throws IOException {
        Path sessionFile = sessionsDir.resolve(session.getId() + JSON_EXTENSION);
        MAPPER.writeValue(sessionFile.toFile(), session);
    }

    public void delete(String sessionId) {
        Path sessionFile = sessionsDir.resolve(sessionId + JSON_EXTENSION);
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            log.log(Level.WARNING, e, () -> "Failed to delete session: " + sessionId);
        }
    }
}
