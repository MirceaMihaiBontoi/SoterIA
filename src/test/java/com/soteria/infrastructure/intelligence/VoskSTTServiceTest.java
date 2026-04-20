package com.soteria.infrastructure.intelligence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VoskSTTService Logic Tests")
class VoskSTTServiceTest {

    @Test
    @DisplayName("Should extract text from valid JSON result")
    void extractTextSuccess() {
        String json = "{\"text\": \"hello world\"}";
        assertEquals("hello world", VoskSTTService.extractText(json));
    }

    @Test
    @DisplayName("Should extract partial from valid JSON partial")
    void extractPartialSuccess() {
        String json = "{\"partial\": \"hello\"}";
        assertEquals("hello", VoskSTTService.extractPartial(json));
    }

    @Test
    @DisplayName("Should handle malformed JSON gracefully")
    void extractMalformed() {
        assertEquals("", VoskSTTService.extractText("invalid-json"));
        assertEquals("", VoskSTTService.extractPartial(""));
    }

    @Test
    @DisplayName("Should handle missing keys")
    void extractMissingKeys() {
        assertEquals("", VoskSTTService.extractText("{}"));
        assertEquals("", VoskSTTService.extractPartial("{\"other\": \"val\"}"));
    }
}
