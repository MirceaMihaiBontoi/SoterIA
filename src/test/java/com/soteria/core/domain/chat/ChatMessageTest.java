package com.soteria.core.domain.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Test
    @DisplayName("ChatMessage should support multilingual emergency phrases (UTF-8)")
    void multilingualSupport() {
        // Most used internet languages & major linguistic families
        Map<String, String> emergencies = Map.of(
            "English", "I need help, it's an emergency!",
            "Spanish", "¡Necesito ayuda, es una emergencia!",
            "Chinese", "我需要帮助，这是紧急情况！",
            "Arabic", "أحتاج إلى المساعدة، إنها حالة طوارئ!",
            "Hindi", "मुझे मदद चाहिए, यह एक आपात स्थिति है!",
            "Portuguese", "Preciso de ajuda, é uma emergência!",
            "French", "J'ai besoin d'aide, c'est une urgence !",
            "Russian", "Мне нужна помощь, это чрезвычайная ситуация!",
            "Japanese", "助けが必要です、緊急事態です！",
            "German", "Ich brauche Hilfe, das ist ein Notfall!"
        );

        // Additional major linguistic families
        Map<String, String> families = Map.of(
            "Korean (Koreanic)", "도움이 필요해요, 응급 상황이에요!",
            "Swahili (Niger-Congo)", "Nahitaji msaada, ni dharura!",
            "Turkish (Turkic)", "Yardıma ihtiyacım var, bu bir acil durum!",
            "Vietnamese (Austroasiatic)", "Tôi cần giúp đỡ, đây là một trường hợp khẩn cấp!",
            "Tamil (Dravidian)", "எனக்கு உதவி தேவை, இது ஒரு அவசர நிலை!",
            "Finnish (Uralic)", "Tarvitsen apua, tämä on hätätilanne!",
            "Indonesian (Austronesian)", "Saya butuh bantuan, ini keadaan darurat!",
            "Greek (Hellenic)", "Βοηθήστε με, είναι έκτακτη ανάγκη!",
            "Hebrew (Semitic)", "עזור لي, זה מצב חירום!"
        );

        emergencies.forEach((lang, text) -> {
            ChatMessage msg = ChatMessage.user(text);
            assertEquals(text, msg.content(), "Failed to store/retrieve " + lang + " text correctly");
        });

        families.forEach((lang, text) -> {
            ChatMessage msg = ChatMessage.user(text);
            assertEquals(text, msg.content(), "Failed to store/retrieve " + lang + " text correctly");
        });
    }
}
