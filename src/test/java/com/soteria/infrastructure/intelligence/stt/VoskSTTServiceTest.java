package com.soteria.infrastructure.intelligence.stt;

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
    @DisplayName("Should handle internet dominant languages in STT extraction")
    void internetDominantLanguagesSTT() {
        String[][] cases = {
            {"English", "I need help", "{\"text\": \"I need help\"}"},
            {"Chinese", "我需要帮助", "{\"text\": \"我需要帮助\"}"},
            {"Spanish", "Necesito ayuda", "{\"text\": \"Necesito ayuda\"}"},
            {"Arabic", "أحتاج إلى مساعدة", "{\"text\": \"أحتاج إلى مساعدة\"}"},
            {"Portuguese", "Preciso de ajuda", "{\"text\": \"Preciso de ajuda\"}"},
            {"French", "J'ai besoin d'aide", "{\"text\": \"J'ai besoin d'aide\"}"},
            {"Japanese", "助けてください", "{\"text\": \"助けてください\"}"},
            {"Russian", "Мне нужна помощь", "{\"text\": \"Мне нужна помощь\"}"},
            {"German", "Ich brauche Hilfe", "{\"text\": \"Ich brauche Hilfe\"}"},
            {"Hindi", "मुझे मदद चाहिए", "{\"text\": \"मुझे मदद चाहिए\"}"}
        };

        for (String[] c : cases) {
            String lang = c[0];
            String expected = c[1];
            String json = c[2];
            assertEquals(expected, VoskSTTService.extractText(json), "Failed extraction for " + lang);
        }
    }

    @Test
    @DisplayName("Should handle diverse linguistic families in STT extraction")
    void linguisticFamiliesSTT() {
        String[][] cases = {
            {"Indo-European (Spanish)", "¡Ayuda!", "{\"text\": \"¡Ayuda!\"}"},
            {"Sino-Tibetan (Mandarin)", "救命！", "{\"text\": \"救命！\"}"},
            {"Afroasiatic (Arabic)", "نجدة!", "{\"text\": \"نجدة!\"}"},
            {"Austronesian (Indonesian)", "Tolong!", "{\"text\": \"Tolong!\"}"},
            {"Dravidian (Telugu)", "సహాయం!", "{\"text\": \"సహాయం!\"}"},
            {"Turkic (Turkish)", "Yardım!", "{\"text\": \"Yardım!\"}"},
            {"Uralic (Finnish)", "Apua!", "{\"text\": \"Apua!\"}"},
            {"Niger-Congo (Swahili)", "Saidia!", "{\"text\": \"Saidia!\"}"},
            {"Japonic (Japanese)", "助けて！", "{\"text\": \"助けて！\"}"},
            {"Koreanic (Korean)", "도와주세요!", "{\"text\": \"도와주세요!\"}"}
        };

        for (String[] c : cases) {
            String family = c[0];
            String expected = c[1];
            String json = c[2];
            assertEquals(expected, VoskSTTService.extractText(json), "Failed extraction for family " + family);
        }
    }

    @Test
    @DisplayName("Should handle multilingual partial results")
    void multilingualPartialSTT() {
        // Partial results in Vosk use the "partial" key
        assertEquals("hel", VoskSTTService.extractPartial("{\"partial\": \"hel\"}"));
        assertEquals("助けて", VoskSTTService.extractPartial("{\"partial\": \"助けて\"}"));
        assertEquals("أحتاج", VoskSTTService.extractPartial("{\"partial\": \"أحتاج\"}"));
        assertEquals("मुझे", VoskSTTService.extractPartial("{\"partial\": \"मुझे\"}"));
    }
}
