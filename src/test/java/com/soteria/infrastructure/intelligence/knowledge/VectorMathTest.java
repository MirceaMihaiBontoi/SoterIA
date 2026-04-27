package com.soteria.infrastructure.intelligence.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VectorMathTest {

    @Test
    @DisplayName("Should compute vector magnitude correctly")
    void testMagnitude() {
        float[] v = {3.0f, 4.0f};
        assertEquals(5.0f, VectorMath.magnitude(v), 0.001f);
        
        assertEquals(0.0f, VectorMath.magnitude(new float[]{0, 0}), 0.001f);
    }

    @Test
    @DisplayName("Should normalize vector to unit length")
    void testNormalize() {
        float[] v = {3.0f, 4.0f};
        float[] normalized = VectorMath.normalize(v);
        
        assertEquals(0.6f, normalized[0], 0.001f);
        assertEquals(0.8f, normalized[1], 0.001f);
        assertEquals(1.0f, VectorMath.magnitude(normalized), 0.001f);
        
        float[] zero = {0, 0};
        float[] expected = {1e-6f, 0};
        assertArrayEquals(expected, VectorMath.normalize(zero));
    }

    @Test
    @DisplayName("Should subtract vectors element-wise")
    void testSubtract() {
        float[] a = {10.0f, 5.0f};
        float[] b = {2.0f, 1.0f};
        float[] expected = {8.0f, 4.0f};
        
        assertArrayEquals(expected, VectorMath.subtract(a, b));
    }

    @Test
    @DisplayName("Should compute centroid of multiple vectors")
    void testComputeCentroid() {
        float[] v1 = {1.0f, 2.0f};
        float[] v2 = {3.0f, 4.0f};
        float[] v3 = {5.0f, 6.0f};
        
        float[] centroid = VectorMath.computeCentroid(List.of(v1, v2, v3));
        
        assertEquals(3.0f, centroid[0], 0.001f);
        assertEquals(4.0f, centroid[1], 0.001f);
        
        assertEquals(0, VectorMath.computeCentroid(List.of()).length);
    }

    @Test
    @DisplayName("Should tokenize and normalize text (remove accents, lowercase)")
    void testTokenizeBasic() {
        String input = "Hola, ¿cómo estás? Esto es una prueba.";
        Set<String> tokens = VectorMath.tokenize(input);
        
        assertTrue(tokens.contains("como"));
        assertTrue(tokens.contains("estas"));
        assertTrue(tokens.contains("prueba"));
        assertTrue(tokens.contains("es"));
        
        assertTrue(VectorMath.tokenize(null).isEmpty());
    }

    @Test
    @DisplayName("Extensive Multilingual Tokenization Test")
    void testTokenizeMultilingual() {
        assertAll("Multilingual tokenization groups",
            () -> {
                // Romance/Western
                verifyTokens("El niño está en la montaña", "nino", "esta", "montana");
                verifyTokens("Le garçon est déjà là-bas", "garcon", "deja", "la-bas");
                verifyTokens("Über die Brücke", "uber", "brucke");
                verifyTokens("Hemorragia grave no braço", "hemorragia", "braco");
                verifyTokens("Saya ada pendarahan hebat", "pendarahan", "hebat");
                verifyTokens("Nina mivuja damu sana", "mivuja", "damu");
            },
            () -> {
                // Slavic, Middle Eastern and Asian
                verifyTokens("Привет мир", "привет", "мир");
                verifyTokens("مساعدة طبية طارئة", "مساعدة", "طبية");
                verifyTokens("İSTANBUL", "istanbul");
                verifyTokens("紧急救援", "紧急救援");
                verifyTokens("救急車を呼んで", "救急車");
                verifyTokens("숨을 쉬지 않아요", "숨을", "않아요");
            },
            () -> {
                // Indic and other scripts
                verifyTokens("\u092e\u0941\u091d\u0947 \u0906\u092a\u093e\u0924\u0915\u093e\u0932\u0940\u0928 \u0938\u0939\u093e\u092f\u0924\u093e \u091a\u093e\u0939\u093f\u090f", "\u092e\u0941\u091d\u0947", "\u0938\u0939\u093e\u092f\u0924\u093e");
                verifyTokens("\u099c\u09b0\u09c1\u09b0\u09bf \u099a\u09bf\u0995\u09bf\u09ce\u09b8\u09be \u09b8\u09b9\u093e\u09af\u09bc\u09a4\u093e", "\u099c\u09b0\u09c1\u09b0\u09bf", "\u09b8\u09b9\u093e\u09af\u09bc\u09a4\u093e");
                verifyTokens("\u0c05\u0c24\u0c4d\u0c2f\u0c35\u0c38\u0c30 \u0c35\u0c48\u0c26\u0c4d\u0c2f \u0c38\u0c39\u0c3e\u0c2f\u0c02", "\u0c35\u0c48\u0c26\u0c4d\u0c2f");
                verifyTokens("Tôi không thở được", "toi", "tho");
            }
        );
    }

    private void verifyTokens(String input, String... expected) {
        Set<String> tokens = VectorMath.tokenize(input);
        for (String e : expected) {
            assertTrue(tokens.contains(e), "Missing token: " + e + " in '" + input + "'");
        }
    }
}
