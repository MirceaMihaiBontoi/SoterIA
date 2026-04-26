package com.soteria.ui.controller;

import com.soteria.infrastructure.intelligence.knowledge.EmergencyKnowledgeBase;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.ui.logic.InferenceEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatController Logic Tests")
class ChatControllerTest {

    @Test
    @DisplayName("Should detect emergency commands in various languages/formats")
    void detectEmergencyCommands() {
        ChatController controller = new ChatController();

        assertTrue(controller.isEmergencyCommand("I need help"));
        assertTrue(controller.isEmergencyCommand("Llama al 112"));
        assertTrue(controller.isEmergencyCommand("This is an emergency"));
        assertTrue(controller.isEmergencyCommand("Necesito una ambulance"));
        assertFalse(controller.isEmergencyCommand("How are you?"));
    }

    @Test
    @DisplayName("Should build context from protocol matches")
    void buildContext() {
        Protocol p = new Protocol();
        p.setId("ID1");
        p.setTitle("Title");
        p.setCategory("Medical");
        p.setSteps(List.of("Step 1"));

        EmergencyKnowledgeBase.ProtocolMatch match = new EmergencyKnowledgeBase.ProtocolMatch(p, "Vector", 0.95f);

        // InferenceEngine.buildProtocolManifest is non-static in current implementation
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null);

        assertTrue(context.contains("ID1"));
        assertTrue(context.contains("Title"));
        assertTrue(context.contains("Step 1"));
    }

    @Test
    @DisplayName("Should handle empty matches in context builder")
    void buildEmptyContext() {
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(), null);
        assertEquals("No specific protocol matched.", context);
    }
}
