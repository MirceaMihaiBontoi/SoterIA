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
    @DisplayName("Should handle protocol progress and locking")
    void protocolProgressLogic() {
        ChatSession session = new ChatSession();
        String protocolId = "FIRE_001";

        assertEquals(0, session.getCurrentStepIndex(), "Should default to 0 if no active emergency");
        
        session.setActiveEmergencyId(protocolId);
        session.setCurrentStepIndex(5);
        assertEquals(5, session.getCurrentStepIndex());

        session.incrementStepIndex(protocolId);
        assertEquals(6, session.getCurrentStepIndex());

        assertFalse(session.isProtocolLocked());
        session.setProtocolLocked(true);
        assertTrue(session.isProtocolLocked());
    }

    @Test
    @DisplayName("Should validate rejected protocol additions")
    void rejectedProtocolsValidation() {
        ChatSession session = new ChatSession();
        
        session.addRejectedProtocolId("PROT_A");
        session.addRejectedProtocolId(""); // Should be ignored
        session.addRejectedProtocolId(null); // Should be ignored
        
        assertEquals(1, session.getRejectedProtocolIds().size());
        assertTrue(session.getRejectedProtocolIds().contains("PROT_A"));
    }

    @Test
    @DisplayName("Should manage categorized context and extensions")
    void contextManagement() {
        ChatSession session = new ChatSession();
        
        session.getActiveCategories().add("MEDICINE");
        session.getCategorizedContext().put("MEDICINE", java.util.List.of("Patient has asthma", "Allergic to penicillin"));
        session.setContextualExtensions("Location: Floor 3, Room 302");

        assertTrue(session.getActiveCategories().contains("MEDICINE"));
        assertEquals(2, session.getCategorizedContext().get("MEDICINE").size());
        assertEquals("Location: Floor 3, Room 302", session.getContextualExtensions());
    }

    @Test
    @DisplayName("Should support multilingual session titles")
    void multilingualTitles() {
        ChatSession session = new ChatSession();
        
        // Testing various scripts in session titles
        java.util.List<String> titles = java.util.List.of(
            "Emergencia en Beijing - 北京紧急情况",
            "حالة طوارئ في القاهرة",
            "Токио - 緊急事態",
            "Emergency in Mumbai - मुंबई में आपातकाल"
        );

        for (String title : titles) {
            session.setTitle(title);
            assertEquals(title, session.getTitle());
        }
    }

    @Test
    @DisplayName("Should support comprehensive Jackson serialization")
    void serializationRoundtrip() throws Exception {
        ChatSession session = new ChatSession();
        session.setTitle("Full State Test");
        session.setActiveEmergencyId("MED_001");
        session.setProtocolLocked(true);
        session.setCurrentStepIndex(3);
        session.addMessage(ChatMessage.user("Help"));
        session.addRejectedProtocolId("SEC_001");
        session.setContextualExtensions("Critical update");
        session.getActiveCategories().add("TRAUMA");
        session.getCategorizedContext().put("TRAUMA", java.util.List.of("Head injury"));

        String json = mapper.writeValueAsString(session);
        ChatSession des = mapper.readValue(json, ChatSession.class);

        assertEquals(session.getId(), des.getId());
        assertEquals("Full State Test", des.getTitle());
        assertEquals("MED_001", des.getActiveEmergencyId());
        assertTrue(des.isProtocolLocked());
        assertEquals(3, des.getCurrentStepIndex());
        assertEquals(1, des.getMessages().size());
        assertTrue(des.getRejectedProtocolIds().contains("SEC_001"));
        assertEquals("Critical update", des.getContextualExtensions());
        assertTrue(des.getActiveCategories().contains("TRAUMA"));
        assertEquals("Head injury", des.getCategorizedContext().get("TRAUMA").get(0));
    }
}
