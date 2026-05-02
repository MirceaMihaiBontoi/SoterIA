package com.soteria.infrastructure.intelligence.llm;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.intelligence.system.LanguageUtils;

import java.util.List;

/**
 * Handles the construction of prompts following the Gemma-3 turn-based format.
 */
public class GemmaPromptBuilder {

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
                **PROTOCOL**:
                [MANIFEST]
                """;
        return template.replace("[PROFILE]", profileContext).replace("[MANIFEST]", context);
    }

    public String buildGemmaPrompt(String staticSystem, String dynamicContext, List<ChatMessage> history, String targetLanguage) {
        StringBuilder sb = new StringBuilder();
        int lastIndex = history.size() - 1;

        for (int i = 0; i <= lastIndex; i++) {
            ChatMessage msg = history.get(i);
            sb.append("<start_of_turn>").append(msg.role()).append('\n');

            if (i == 0 && "user".equals(msg.role())) {
                sb.append("## SYSTEM_INSTRUCTIONS\n")
                        .append(staticSystem)
                        .append("\n\n");
            }

            if (i == lastIndex && "user".equals(msg.role())) {
                sb.append("## SITUATIONAL_CONTEXT\n")
                        .append(dynamicContext)
                        .append("\n\n## USER_INPUT\n");
            } else if (i == 0 && "user".equals(msg.role())) {
                sb.append("## FIRST_USER_MESSAGE\n");
            }

            if (i == lastIndex && "user".equals(msg.role())) {
                sb.append(msg.content())
                  .append("\n\nReply in ").append(targetLanguage).append(" only. One or two short sentences; no parentheses.")
                  .append("<end_of_turn>\n");
            } else {
                sb.append(msg.content()).append("<end_of_turn>\n");
            }
        }

        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }
}
