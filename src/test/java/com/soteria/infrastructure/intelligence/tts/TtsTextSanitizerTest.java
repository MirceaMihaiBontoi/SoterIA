package com.soteria.infrastructure.intelligence.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TtsTextSanitizerTest {

    @Test
    @DisplayName("Should return empty string for null input")
    void nullInputReturnsEmpty() {
        assertEquals("", TtsTextSanitizer.sanitize(null));
        assertEquals("", TtsTextSanitizer.sanitize(null, "en"));
    }

    @Test
    @DisplayName("Should return empty string for empty input")
    void emptyInputReturnsEmpty() {
        assertEquals("", TtsTextSanitizer.sanitize(""));
        assertEquals("", TtsTextSanitizer.sanitize("   "));
    }

    @Test
    @DisplayName("Should normalize and trim whitespace")
    void normalizesAndTrims() {
        assertEquals("Hello world", TtsTextSanitizer.sanitize("  Hello world  "));
        assertEquals("Test", TtsTextSanitizer.sanitize("\n\tTest\n\t"));
    }

    @Test
    @DisplayName("Should remove control characters except newline, tab, carriage return")
    void removesControlCharacters() {
        String input = "Hello\u0000World\u0001Test";
        String result = TtsTextSanitizer.sanitize(input);
        assertFalse(result.contains("\u0000"));
        assertFalse(result.contains("\u0001"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
    }

    @Test
    @DisplayName("Should remove replacement character U+FFFD")
    void removesReplacementCharacter() {
        String input = "Hello\uFFFDWorld";
        String result = TtsTextSanitizer.sanitize(input);
        assertFalse(result.contains("\uFFFD"));
        assertEquals("HelloWorld", result);
    }

    @Test
    @DisplayName("Greek characters are kept in PERMISSIVE mode, removed in strict modes")
    void greekCharacterHandling() {
        String input = "Hello Γεια σου World";
        
        // In PERMISSIVE mode (default/en), Greek is KEPT
        String enResult = TtsTextSanitizer.sanitize(input, "en");
        assertTrue(enResult.contains("Γ"));
        assertTrue(enResult.contains("ε"));
        assertTrue(enResult.contains("Hello"));
        
        // In JA mode (strict), Greek is REMOVED
        String jaResult = TtsTextSanitizer.sanitize(input, "ja");
        assertFalse(jaResult.contains("Γ"));
        assertFalse(jaResult.contains("ε"));
        assertTrue(jaResult.contains("Hello"));
        assertTrue(jaResult.contains("World"));
    }

    @Test
    @DisplayName("Should limit to 480 code points")
    void limitsCodePoints() {
        String longText = "a".repeat(500);
        String result = TtsTextSanitizer.sanitize(longText);
        assertTrue(result.length() <= 480);
    }

    @Test
    @DisplayName("PERMISSIVE mode: Should keep most characters")
    void permissiveModeKeepsCharacters() {
        String input = "Hello 123 test! ¿Cómo estás?";
        String result = TtsTextSanitizer.sanitize(input, "en");
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("123"));
        assertTrue(result.contains("¿"));
        assertTrue(result.contains("Cómo"));
    }

    @Test
    @DisplayName("JA mode: Should keep Hiragana, Katakana, Han, and basic Latin")
    void japaneseModeFiltering() {
        String input = "こんにちは World 你好 123";
        String result = TtsTextSanitizer.sanitize(input, "ja");
        
        // Should keep Japanese scripts
        assertTrue(result.contains("こんにちは"));
        // Should keep basic Latin
        assertTrue(result.contains("World"));
        // Should keep Han (used in Japanese)
        assertTrue(result.contains("你好"));
        // Should keep digits
        assertTrue(result.contains("123"));
    }

    @Test
    @DisplayName("ZH mode: Should only keep Han characters, digits, and punctuation")
    void chineseHanziOnlyMode() {
        String input = "你好 Hello 123 世界！";
        String result = TtsTextSanitizer.sanitize(input, "zh");
        
        // Should keep Han
        assertTrue(result.contains("你好"));
        assertTrue(result.contains("世界"));
        // Should keep digits
        assertTrue(result.contains("123"));
        // Should keep punctuation
        assertTrue(result.contains("！"));
        // Should remove Latin (prevents crashes)
        assertFalse(result.contains("Hello"));
    }

    @Test
    @DisplayName("ZH mode: Auto-detect Han in text and apply strict filtering")
    void autoDetectHanAppliesStrictMode() {
        // Even without zh hint, if text contains Han, apply ZH_HANZI_ONLY
        String input = "Hello 你好 World";
        String result = TtsTextSanitizer.sanitize(input, "en"); // hint is en, but contains Han
        
        // Should keep Han
        assertTrue(result.contains("你好"));
        // Should remove Latin (auto-detected Han triggers strict mode)
        assertFalse(result.contains("Hello"));
        assertFalse(result.contains("World"));
    }

    @Test
    @DisplayName("Should preserve punctuation in all modes")
    void preservesPunctuation() {
        String input = "Hello, world! ¿How are you? 你好！";
        
        String enResult = TtsTextSanitizer.sanitize(input, "en");
        assertTrue(enResult.contains(","));
        assertTrue(enResult.contains("!"));
        assertTrue(enResult.contains("?"));
        
        String zhResult = TtsTextSanitizer.sanitize("你好，世界！", "zh");
        assertTrue(zhResult.contains("，"));
        assertTrue(zhResult.contains("！"));
    }

    @Test
    @DisplayName("Should preserve whitespace and digits")
    void preservesWhitespaceAndDigits() {
        String input = "Test 123 456";
        String result = TtsTextSanitizer.sanitize(input);
        assertTrue(result.contains(" "));
        assertTrue(result.contains("123"));
        assertTrue(result.contains("456"));
    }

    @Test
    @DisplayName("Should handle combining marks (diacritics)")
    void handlesCombiningMarks() {
        String input = "café naïve";
        String result = TtsTextSanitizer.sanitize(input, "en");
        assertTrue(result.contains("café"));
        assertTrue(result.contains("naïve"));
    }

    @Test
    @DisplayName("Should handle multilingual emergency phrases")
    void multilingualEmergencyPhrases() {
        // Spanish
        String es = TtsTextSanitizer.sanitize("¡Emergencia! Necesito ayuda.", "es");
        assertTrue(es.contains("Emergencia"));
        assertTrue(es.contains("ayuda"));
        
        // French
        String fr = TtsTextSanitizer.sanitize("Urgence! J'ai besoin d'aide.", "fr");
        assertTrue(fr.contains("Urgence"));
        assertTrue(fr.contains("aide"));
        
        // Italian
        String it = TtsTextSanitizer.sanitize("Emergenza! Ho bisogno di aiuto.", "it");
        assertTrue(it.contains("Emergenza"));
        assertTrue(it.contains("aiuto"));
    }

    @Test
    @DisplayName("Should handle edge case: only invalid characters")
    void onlyInvalidCharacters() {
        String input = "\u0000\u0001\uFFFD";
        String result = TtsTextSanitizer.sanitize(input);
        assertEquals("", result);
    }

    @Test
    @DisplayName("Should handle NFC normalization")
    void nfcNormalization() {
        // Composed vs decomposed forms
        String composed = "é"; // U+00E9
        String decomposed = "e\u0301"; // e + combining acute
        
        String result1 = TtsTextSanitizer.sanitize(composed);
        String result2 = TtsTextSanitizer.sanitize(decomposed);
        
        // Both should normalize to same form
        assertEquals(result1, result2);
    }
}
