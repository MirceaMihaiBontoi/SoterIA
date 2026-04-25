package com.soteria.infrastructure.persistence;

import com.soteria.infrastructure.intelligence.ChatMessage;
import com.soteria.infrastructure.intelligence.ChatSession;
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
}
