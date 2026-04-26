package com.soteria.infrastructure.intelligence.llm;

import com.soteria.core.domain.chat.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalBrainService Logic Tests")
class LocalBrainServiceTest {

    @Test
    @DisplayName("Should build correct Gemma prompt from history")
    void buildGemmaPrompt() {
        String system = "Answer in Spanish.";
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("Help me"));
        history.add(ChatMessage.model("Yes"));
        history.add(ChatMessage.user("Bleeding"));

        String result = LocalBrainService.buildGemmaPrompt(system, "Protocol: NONE", history);

        // Verify role markers and the structured framing the prompt builder applies:
        // first user turn carries SYSTEM_INSTRUCTIONS + FIRST_USER_MESSAGE,
        // last user turn carries SITUATIONAL_CONTEXT + USER_INPUT.
        assertTrue(result.contains("<start_of_turn>user\n## SYSTEM_INSTRUCTIONS\nAnswer in Spanish.\n\n## FIRST_USER_MESSAGE\nHelp me<end_of_turn>"));
        assertTrue(result.contains("<start_of_turn>model\nYes<end_of_turn>"));
        assertTrue(result.contains("<start_of_turn>user\n## SITUATIONAL_CONTEXT\nProtocol: NONE\n\n## USER_INPUT\nBleeding<end_of_turn>"));

        // Verify final model trigger
        assertTrue(result.endsWith("<start_of_turn>model\n"));
    }
}
