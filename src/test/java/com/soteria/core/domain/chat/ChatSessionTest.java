package com.soteria.core.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatSession Model Tests")
class ChatSessionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Should initialize with default values")
    void defaultInitialization() {
        ChatSession session = new ChatSession();
        assertNotNull(session.getId());
        assertTrue(session.getTimestamp() > 0);
        assertNotNull(session.getMessages());
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    @DisplayName("Should handle message additions")
    void messageAddition() {
        ChatSession session = new ChatSession();
        ChatMessage msg = ChatMessage.user("Hello");
        session.addMessage(msg);
        
        assertEquals(1, session.getMessages().size());
        assertEquals("Hello", session.getMessages().get(0).content());
    }

    @Test
    @DisplayName("Should support Jackson serialization")
    void serializationRoundtrip() throws Exception {
        ChatSession session = new ChatSession();
        session.setTitle("Test Session");
        session.addMessage(ChatMessage.user("Query"));
        session.addMessage(ChatMessage.model("Response"));
        session.getRejectedProtocolIds().add("PROT_001");

        String json = mapper.writeValueAsString(session);
        ChatSession deserialized = mapper.readValue(json, ChatSession.class);

        assertEquals(session.getId(), deserialized.getId());
        assertEquals("Test Session", deserialized.getTitle());
        assertEquals(2, deserialized.getMessages().size());
        assertTrue(deserialized.getRejectedProtocolIds().contains("PROT_001"));
    }
}
