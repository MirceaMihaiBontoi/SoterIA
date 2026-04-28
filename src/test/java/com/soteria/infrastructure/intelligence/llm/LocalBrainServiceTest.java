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

        String result = LocalBrainService.buildGemmaPrompt(system, "Protocol: NONE", history, "Spanish");

        // Verify role markers and the structured framing the prompt builder applies:
        // first user turn carries SYSTEM_INSTRUCTIONS + FIRST_USER_MESSAGE,
        // last user turn carries SITUATIONAL_CONTEXT + USER_INPUT.
        assertTrue(result.contains("<start_of_turn>user\n## SYSTEM_INSTRUCTIONS\nAnswer in Spanish.\n\n## FIRST_USER_MESSAGE\nHelp me<end_of_turn>"));
        assertTrue(result.contains("<start_of_turn>model\nYes<end_of_turn>"));
        assertTrue(result.contains("<start_of_turn>user\n## SITUATIONAL_CONTEXT\nProtocol: NONE\n\n## USER_INPUT\nBleeding\n\n(Respond in Spanish. 1-2 natural sentences.)<end_of_turn>"));

        // Verify final model trigger
        assertTrue(result.endsWith("<start_of_turn>model\n"));
    }

    @Test
    @DisplayName("Should build correct Gemma prompt for single turn")
    void buildGemmaPromptSingleTurn() {
        String system = "Helpful Assistant.";
        List<ChatMessage> history = List.of(ChatMessage.user("Hi"));

        String result = LocalBrainService.buildGemmaPrompt(system, "No context", history, "English");

        assertTrue(result.contains("<start_of_turn>user\n## SYSTEM_INSTRUCTIONS\nHelpful Assistant.\n\n## SITUATIONAL_CONTEXT\nNo context\n\n## USER_INPUT\nHi\n\n(Respond in English. 1-2 natural sentences.)<end_of_turn>"));
        assertTrue(result.endsWith("<start_of_turn>model\n"));
    }

    @Test
    @DisplayName("Should handle internet dominant languages in prompt building")
    void internetDominantLanguagesPrompt() {
        String[][] cases = {
            {"English", "I need help", "Emergency: Heart attack"},
            {"Chinese", "我需要帮助", "紧急情况：心脏病发作"},
            {"Spanish", "Necesito ayuda", "Emergencia: Ataque al corazón"},
            {"Arabic", "أحتاج إلى مساعدة", "حالة طوارئ: نوبة قلبية"},
            {"Portuguese", "Preciso de ajuda", "Emergência: Ataque cardíaco"},
            {"French", "J'ai besoin d'aide", "Urgence : Crise cardiaque"},
            {"Japanese", "助けてください", "緊急：心臓麻痺"},
            {"Russian", "Мне нужна помощь", "Чрезвычайная ситуация: Сердечный приступ"},
            {"German", "Ich brauche Hilfe", "Notfall: Herzinfarkt"},
            {"Hindi", "मुझे मदद चाहिए", "आपातकालीन: दिल का दौरा"}
        };

        for (String[] c : cases) {
            String lang = c[0];
            String input = c[1];
            String context = c[2];
            String system = "Respond in " + lang;
            
            List<ChatMessage> history = List.of(ChatMessage.user(input));
            String result = LocalBrainService.buildGemmaPrompt(system, context, history, lang);
            
            assertTrue(result.contains(lang), "Prompt should contain language instruction for " + lang);
            assertTrue(result.contains(input), "Prompt should contain user input for " + lang);
            assertTrue(result.contains(context), "Prompt should contain situational context for " + lang);
            assertTrue(result.startsWith("<start_of_turn>user"), "Should start with user turn");
            assertTrue(result.endsWith("<start_of_turn>model\n"), "Should end with model trigger");
        }
    }

    @Test
    @DisplayName("Should handle diverse linguistic families in prompt building")
    void linguisticFamiliesPrompt() {
        String[][] cases = {
            {"Indo-European (Spanish)", "¡Ayuda!", "Accidente"},
            {"Sino-Tibetan (Mandarin)", "救命！", "事故"},
            {"Afroasiatic (Arabic)", "نجدة!", "حادث"},
            {"Austronesian (Indonesian)", "Tolong!", "Kecelakaan"},
            {"Dravidian (Telugu)", "సహాయం!", "ప్రమాదం"},
            {"Turkic (Turkish)", "Yardım!", "Kaza"},
            {"Uralic (Finnish)", "Apua!", "Onnettomuus"},
            {"Niger-Congo (Swahili)", "Saidia!", "Ajali"},
            {"Japonic (Japanese)", "助けて！", "事故"},
            {"Koreanic (Korean)", "도와주세요!", "사고"}
        };

        for (String[] c : cases) {
            String family = c[0];
            String input = c[1];
            String context = c[2];
            
            List<ChatMessage> history = List.of(ChatMessage.user(input));
            String result = LocalBrainService.buildGemmaPrompt("Emergency mode.", context, history, family);
            
            assertNotNull(result);
            assertTrue(result.contains(input), "Should contain " + family + " input");
            assertTrue(result.contains(context), "Should contain " + family + " context");
            assertTrue(result.contains("<start_of_turn>user"), "Missing turn marker for " + family);
        }
    }
}
