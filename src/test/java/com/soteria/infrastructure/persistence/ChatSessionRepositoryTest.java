package com.soteria.infrastructure.persistence;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatSessionRepository Tests")
class ChatSessionRepositoryTest {

    @TempDir
    Path tempDir;

    private ChatSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatSessionRepository(tempDir);
    }

    @Test
    @DisplayName("Should save and reload a session")
    void saveAndLoad() {
        ChatSession session = new ChatSession();
        session.setTitle("Test");
        session.addMessage(ChatMessage.user("Hi"));

        repository.saveSession(session);

        List<ChatSession> sessions = repository.getAllSessions();
        assertEquals(1, sessions.size());
        assertEquals(session.getId(), sessions.get(0).getId());
        assertEquals("Hi", sessions.get(0).getMessages().get(0).content());
    }

    @Test
    @DisplayName("Should list sessions in chronological order (descending)")
    void listOrdering() {
        ChatSession oldSession = new ChatSession();
        oldSession.setTimestamp(System.currentTimeMillis() - 1000);
        ChatSession newSession = new ChatSession();

        repository.saveSession(oldSession);
        repository.saveSession(newSession);

        List<ChatSession> sessions = repository.getAllSessions();
        assertEquals(2, sessions.size());
        assertEquals(newSession.getId(), sessions.get(0).getId(), "Newest session should be first");
    }

    @Test
    @DisplayName("Should delete a session file")
    void deleteSession() {
        ChatSession session = new ChatSession();
        repository.saveSession(session);

        repository.delete(session.getId());

        assertTrue(repository.getAllSessions().isEmpty());
    }

    @Test
    @DisplayName("Should handle multilingual content correctly (Unicode)")
    void multilingualPersistence() {
        ChatSession session = new ChatSession();
        // Title: Emergency in Arabic
        session.setTitle("\u062d\u0627\u0644\u0629 \u0637\u0648\u0627\u0631\u0626");
        
        // Messages in various scripts
        session.addMessage(ChatMessage.user("\u6551\u547d")); // Help (Chinese)
        session.addMessage(ChatMessage.user("\u041f\u043e\u0436\u0430\u0440")); // Fire (Russian)
        session.addMessage(ChatMessage.user("\u092c\u091a\u093e\u0913")); // Help (Hindi)
        session.addMessage(ChatMessage.user("\u52a9\u3051\u3066")); // Help (Japanese)
        session.addMessage(ChatMessage.user("Aide-moi !")); // French
        session.addMessage(ChatMessage.user("\u12a5\u122d\u12f3\u1273")); // Help (Amharic)

        repository.saveSession(session);

        ChatSession loaded = repository.getAllSessions().get(0);
        assertEquals(session.getTitle(), loaded.getTitle());
        assertEquals("\u6551\u547d", loaded.getMessages().get(0).content());
        assertEquals("\u041f\u043e\u0436\u0430\u0440", loaded.getMessages().get(1).content());
        assertEquals("\u092c\u091a\u093e\u0913", loaded.getMessages().get(2).content());
        assertEquals("\u52a9\u3051\u3066", loaded.getMessages().get(3).content());
        assertEquals("Aide-moi !", loaded.getMessages().get(4).content());
        assertEquals("\u12a5\u122d\u12f3\u1273", loaded.getMessages().get(5).content());
    }

    @Test
    @DisplayName("Should skip corrupted JSON files instead of failing")
    void corruptedFileHandling() throws Exception {
        // Create a valid session
        ChatSession session = new ChatSession();
        repository.saveSession(session);

        // Create a corrupted file (invalid JSON)
        Path corrupted = tempDir.resolve("corrupted.json");
        java.nio.file.Files.writeString(corrupted, "{ invalid json content }");

        List<ChatSession> sessions = repository.getAllSessions();
        
        // Should only load the valid one
        assertEquals(1, sessions.size());
        assertEquals(session.getId(), sessions.get(0).getId());
    }
}
