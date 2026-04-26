package com.soteria.ui.controller;

import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.application.chat.InferenceEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatController Logic Tests")
class ChatControllerTest {

    @Test
    @DisplayName("Should build context from protocol matches")
    void buildContext() {
        Protocol p = new Protocol();
        p.setId("ID1");
        p.setTitle("Title");
        p.setCategory("Medical");
        p.setSteps(List.of("Step 1"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "Vector", 0.95f);

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
