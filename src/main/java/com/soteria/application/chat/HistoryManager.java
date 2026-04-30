package com.soteria.application.chat;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the relevance and filtering of chat history to optimize context limit.
 */
public class HistoryManager {

    public List<ChatMessage> filterRelevantHistory(List<ChatMessage> history, String currentQuery, ChatSession session) {
        final List<ChatMessage> filtered = new ArrayList<>();
        final Set<String> relevantTurns = new HashSet<>();

        if (session != null && session.getCategorizedContext() != null) {
            session.getCategorizedContext().values().forEach(relevantTurns::addAll);
        }

        int startCoherence = Math.max(0, history.size() - 4);

        int i = 0;
        while (i < history.size()) {
            ChatMessage msg = history.get(i);
            if (shouldIncludeMessage(i, startCoherence, msg, currentQuery, relevantTurns)) {
                filtered.add(msg);
                // If it's a relevant user message, also add the following model response
                if (isRelevantUserMessage(msg, currentQuery, relevantTurns)
                        && (i + 1 < history.size())
                        && "model".equals(history.get(i + 1).role())) {
                    filtered.add(history.get(i + 1));
                    i++; // Skip the model response
                }
            }
            i++;
        }

        if (filtered.isEmpty()) {
            filtered.add(ChatMessage.user(currentQuery));
        }
        return filtered;
    }

    private boolean shouldIncludeMessage(int index, int coherenceStart, ChatMessage msg,
                                        String query, Set<String> relevantTurns) {
        return index >= coherenceStart || isRelevantUserMessage(msg, query, relevantTurns);
    }

    private boolean isRelevantUserMessage(ChatMessage msg, String query, Set<String> relevantTurns) {
        return "user".equals(msg.role()) && (msg.content().equals(query) || relevantTurns.contains(msg.content()));
    }
}
