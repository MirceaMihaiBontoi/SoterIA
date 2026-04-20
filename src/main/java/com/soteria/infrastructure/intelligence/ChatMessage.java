package com.soteria.infrastructure.intelligence;

/**
 * A single turn in a conversation with the local brain.
 * Role is either "user" or "model" (Gemma chat template roles).
 */
public record ChatMessage(String role, String content) {
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage model(String content) {
        return new ChatMessage("model", content);
    }
}
