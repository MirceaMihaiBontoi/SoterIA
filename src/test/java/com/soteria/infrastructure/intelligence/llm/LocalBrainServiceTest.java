package com.soteria.infrastructure.intelligence.llm;

import com.soteria.core.domain.chat.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GemmaPromptBuilder Logic Tests")
class LocalBrainServiceTest {

    private static final String PREFIX_SYSTEM_TURN = "<start_of_turn>system\n";
    private static final String PREFIX_MODEL_TURN = "<start_of_turn>model\n";
    private static final String LANG_SPANISH = "Spanish";

    private GemmaPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        this.promptBuilder = new GemmaPromptBuilder();
    }

    @Test
    @DisplayName("Should build correct Gemma 4 prompt from history")
    void buildGemmaPrompt() {
        String system = "Answer in Spanish.";
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("Help me"));
        history.add(ChatMessage.model("Yes"));
        history.add(ChatMessage.user("Bleeding"));

        String result = promptBuilder.buildGemmaPrompt(system, "Protocol: NONE", history, LANG_SPANISH);

        assertTrue(result.startsWith(PREFIX_SYSTEM_TURN));
        assertTrue(result.contains("Answer in Spanish."));
        assertTrue(result.contains("Answer in Spanish.\n\n## Conversation history<end_of_turn>\n"),
                "History heading at end of system when prior turns exist");
        assertTrue(result.contains("<start_of_turn>user\nHelp me<end_of_turn>"));
        assertTrue(result.contains(PREFIX_MODEL_TURN + "Yes<end_of_turn>"));
        assertTrue(result.contains("Protocol: NONE"));
        assertTrue(result.contains("# USER_MESSAGE\n\nBleeding"));
        assertTrue(result.contains("Reply in " + LANG_SPANISH + " only."));
        assertTrue(result.endsWith(PREFIX_MODEL_TURN));
    }

    @Test
    @DisplayName("When history starts with assistant, history heading stays in system turn")
    void historyHeadingWhenFirstTurnIsModel() {
        String system = "Sys.";
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.model("Greeting."));
        history.add(ChatMessage.user("Hi"));
        history.add(ChatMessage.user("Fire"));

        String result = promptBuilder.buildGemmaPrompt(system, "CTX", history, LANG_SPANISH);

        assertTrue(result.contains("Sys.\n\n## Conversation history<end_of_turn>\n"));
        assertTrue(result.contains("<start_of_turn>model\nGreeting.<end_of_turn>"));
        assertTrue(result.contains("# USER_MESSAGE\n\nFire"));
    }

    @Test
    @DisplayName("Should build correct Gemma 4 prompt for single turn")
    void buildGemmaPromptSingleTurn() {
        String system = "Helpful Assistant.";
        List<ChatMessage> history = List.of(ChatMessage.user("Hi"));

        String result = promptBuilder.buildGemmaPrompt(system, "No context", history, "English");

        assertTrue(result.startsWith(PREFIX_SYSTEM_TURN + "Helpful Assistant."));
        assertFalse(result.contains("## Conversation history"), "No history heading when there is only the current user turn");
        assertTrue(result.contains("No context"));
        assertTrue(result.contains("# USER_MESSAGE\n\nHi"));
        assertTrue(result.contains("Reply in English only."));
        assertTrue(result.endsWith(PREFIX_MODEL_TURN));
    }

    @Test
    @DisplayName("Should handle internet dominant languages in prompt building")
    void internetDominantLanguagesPrompt() {
        String[][] cases = {
            {"English", "I need help", "Emergency: Heart attack"},
            {"Chinese", "我需要帮助", "紧急情况：心脏病发作"},
            {LANG_SPANISH, "Necesito ayuda", "Emergencia: Ataque al corazón"},
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
            String result = promptBuilder.buildGemmaPrompt(system, context, history, lang);

            assertTrue(result.contains(lang), "Prompt should contain language instruction for " + lang);
            assertTrue(result.contains(input), "Prompt should contain user input for " + lang);
            assertTrue(result.contains(context), "Prompt should contain situational context for " + lang);
            assertTrue(result.startsWith(PREFIX_SYSTEM_TURN), "Should start with system turn for " + lang);
            assertTrue(result.endsWith(PREFIX_MODEL_TURN), "Should end with model trigger for " + lang);
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
            String result = promptBuilder.buildGemmaPrompt("Emergency mode.", context, history, family);

            assertNotNull(result);
            assertTrue(result.contains(input), "Should contain " + family + " input");
            assertTrue(result.contains(context), "Should contain " + family + " context");
            assertTrue(result.contains(PREFIX_SYSTEM_TURN), "Missing system turn for " + family);
            assertTrue(result.contains("<start_of_turn>user\n"), "Missing user turn for " + family);
        }
    }
}
