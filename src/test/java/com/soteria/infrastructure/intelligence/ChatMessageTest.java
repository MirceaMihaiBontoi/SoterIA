package com.soteria.infrastructure.intelligence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatMessage Record Tests")
class ChatMessageTest {

    @Test
    @DisplayName("Factory methods should set correct roles")
    void factoryMethodsSetCorrectRoles() {
        ChatMessage userMsg = ChatMessage.user("Hello");
        ChatMessage modelMsg = ChatMessage.model("Hi there");

        assertEquals("user", userMsg.role());
        assertEquals("model", modelMsg.role());
        assertEquals("Hello", userMsg.content());
        assertEquals("Hi there", modelMsg.content());
    }

    @Test
    @DisplayName("ChatMessage should be a valid Java record")
    void recordBehavior() {
        ChatMessage msg1 = new ChatMessage("user", "test");
        ChatMessage msg2 = new ChatMessage("user", "test");
        ChatMessage msg3 = new ChatMessage("model", "test");

        assertEquals(msg1, msg2, "Records with same content should be equal");
        assertNotEquals(msg1, msg3, "Different roles should not be equal");
        assertEquals(msg1.hashCode(), msg2.hashCode());
        assertTrue(msg1.toString().contains("role=user"));
        assertTrue(msg1.toString().contains("content=test"));
    }
}
