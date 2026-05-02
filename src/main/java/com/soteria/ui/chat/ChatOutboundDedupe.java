package com.soteria.ui.chat;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe guard against the same normalized utterance being submitted twice within
 * {@link ChatInputGuards#RAPID_SUBMIT_GUARD_MS} (typed send + STT).
 */
final class ChatOutboundDedupe {

    private String lastKey = "";
    private long lastAtMs = 0;

    /**
     * @param rawText user or STT string
     * @param fineLog   log template with {@code {0}} = instance id, when duplicate is rejected
     * @return {@code true} if the submit should proceed; {@code false} if it is a rapid duplicate
     */
    synchronized boolean tryAccept(String rawText, Logger logger, String instanceId, String fineLog) {
        String key = ChatInputGuards.normalizeForDedupe(rawText);
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastAtMs < ChatInputGuards.RAPID_SUBMIT_GUARD_MS) {
            logger.log(Level.FINE, fineLog, instanceId);
            return false;
        }
        lastKey = key;
        lastAtMs = now;
        return true;
    }
}
