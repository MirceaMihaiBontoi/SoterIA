package com.soteria.infrastructure.intelligence.llm;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.intelligence.system.LanguageUtils;

import java.util.List;

/**
 * Builds prompts for Gemma 4 instruction-tuned models: explicit {@code system} turn, then
 * {@code user} / {@code model} turns with {@code <start_of_turn>} / {@code <end_of_turn>}
 * (see Google / HF chat template for Gemma 4).
 */
public class GemmaPromptBuilder {

    private static final String END_OF_TURN = "<end_of_turn>\n";

    /** Delimits the caller's words from injected situational data in the final user turn. */
    private static final String USER_MESSAGE_HEADING = "# USER_MESSAGE";

    /**
     * Substrings that must terminate assistant decoding for Gemma chat-tuned models (llama.cpp {@code stop}).
     * Include variants the tokenizer may emit so generation stops before they appear in user-visible output.
     */
    protected static final String[] GEMMA_ASSISTANT_STOP_SEQUENCES = {
            "<end_of_turn>",
            "\\end_of_turn>",
    };

    public String preparePrompt(List<ChatMessage> history, String context, String targetLanguage, UserData profile) {
        String profileContext = (profile != null)
                ? String.format("USER DATA: Name: %s | Medical Info: %s", profile.fullName(), profile.medicalInfo())
                : "No user profile data available.";

        String staticSystem = buildStaticInstructions(targetLanguage);
        String dynamicContext = buildDynamicContext(profileContext, context);
        return buildGemmaPrompt(staticSystem, dynamicContext, history, targetLanguage);
    }

    private String buildStaticInstructions(String targetLanguage) {
        String staticPrompt = """
                ## ROLE: EMERGENCY_DISPATCHER
                You are a calm and supportive human dispatcher helping someone in a crisis.
                
                ### CRITICAL INSTRUCTIONS
                1. **LANGUAGE**: Always respond in [TARGET_LANG]. Even if the instructions are in English, your response MUST be in [TARGET_LANG].
                2. **STAY IN CHARACTER**: Talk like a real person on a phone call. Be supportive, empathetic, and direct.
                3. **USE THE PROTOCOL**: Use the provided protocol as your technical knowledge base. Don't mention "steps" or "protocols". Just use the information to give the best advice for the situation.
                4. **BREVITY**: Keep your response to 1 or 2 natural sentences. Focus on the immediate action the user should take.
                5. **NO PARENTHESES**: Do not use `(` or `)` anywhere in your reply. No asides, translations, pronunciation hints, alternate wording, or English glosses in parentheses—only plain spoken lines.
                """;
        staticPrompt = staticPrompt.replace("[TARGET_LANG]", targetLanguage);
        if (isChineseTarget(targetLanguage)) {
            staticPrompt += """
                    
                    6. **CHINESE SCRIPT ONLY**: Use Chinese characters (汉字) only. No pinyin, romanization, or Latin/English mixed into the answer.
                    """;
        }
        return staticPrompt;
    }

    private static boolean isChineseTarget(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return false;
        }
        return "zh".equals(LanguageUtils.isoCode(targetLanguage));
    }

    private String buildDynamicContext(String profileContext, String context) {
        String template = """
                ### SITUATIONAL_DATA
                **USER_BACKGROUND_PROFILE (DO NOT MENTION UNLESS RELEVANT)**: [PROFILE]
                **EMERGENCY ID**:
                [MANIFEST]
                """;
        return template.replace("[PROFILE]", profileContext).replace("[MANIFEST]", context);
    }

    /**
     * Gemma 4 layout: system turn first, then alternating user/model turns; generation after final
     * {@code <start_of_turn>model} with no closing {@code <end_of_turn>}.
     */
    public String buildGemmaPrompt(String staticSystem, String dynamicContext, List<ChatMessage> history, String targetLanguage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<start_of_turn>system\n")
                .append(staticSystem.stripTrailing());
        if (history.size() > 1) {
            sb.append("\n\n## Conversation history");
        }
        sb.append(END_OF_TURN);

        int lastIndex = history.size() - 1;
        for (int i = 0; i <= lastIndex; i++) {
            ChatMessage msg = history.get(i);
            if ("user".equals(msg.role())) {
                sb.append("<start_of_turn>user\n");
                if (i == lastIndex) {
                    sb.append(dynamicContext.stripTrailing())
                            .append("\n\n")
                            .append(USER_MESSAGE_HEADING)
                            .append("\n\n")
                            .append(msg.content())
                            .append("\n\nReply in ")
                            .append(targetLanguage)
                            .append(" only. One or two short sentences; no parentheses.")
                            .append(END_OF_TURN);
                } else {
                    sb.append(msg.content()).append(END_OF_TURN);
                }
            } else if ("model".equals(msg.role())) {
                sb.append("<start_of_turn>model\n");
                sb.append(msg.content())
                        .append(END_OF_TURN);
            }
        }

        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }
}
