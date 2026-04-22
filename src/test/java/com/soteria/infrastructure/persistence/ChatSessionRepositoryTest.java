package com.soteria.infrastructure.persistence;

import com.soteria.infrastructure.intelligence.ChatMessage;
import com.soteria.infrastructure.intelligence.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
    void saveAndLoad() throws IOException {
        ChatSession session = new ChatSession();
        session.setTitle("Test");
        session.addMessage(ChatMessage.user("Hi"));
        
        repository.save(session);
        
        List<ChatSession> sessions = repository.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(session.getId(), sessions.get(0).getId());
        assertEquals("Hi", sessions.get(0).getMessages().get(0).content());
    }

    @Test
    @DisplayName("Should list sessions in chronological order (descending)")
    void listOrdering() throws IOException, InterruptedException {
        ChatSession oldSession = new ChatSession();
        Thread.sleep(10); // Ensure different timestamps
        ChatSession newSession = new ChatSession();
        
        repository.save(oldSession);
        repository.save(newSession);
        
        List<ChatSession> sessions = repository.listSessions();
        assertEquals(2, sessions.size());
        assertEquals(newSession.getId(), sessions.get(0).getId(), "Newest session should be first");
    }

    @Test
    @DisplayName("Should delete a session file")
    void deleteSession() throws IOException {
        ChatSession session = new ChatSession();
        repository.save(session);
        
        repository.delete(session.getId());
        
        assertTrue(repository.listSessions().isEmpty());
    }
}
