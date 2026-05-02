package com.soteria.ui.chat;

/**
 * Rapid double-submit protection and STT wake-phrase echo suppression for chat / voice paths.
 */
final class ChatInputGuards {

    /** Ignore repeated sends within this window after normalization (Enter + STT duplicates). */
    static final long RAPID_SUBMIT_GUARD_MS = 450;

    private ChatInputGuards() {
    }

    /**
     * Collapses whitespace for trivial duplicate detection (rapid double Enter, echo submits).
     *
     * @param text raw user or STT string
     * @return normalized key, or empty when null/blank
     */
    static String normalizeForDedupe(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * {@code true} when the transcript is only the assistant wake phrase and should not be posted as user text.
     */
    static boolean isWakePhraseEchoTranscript(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String clean = text.toLowerCase().replaceAll("[^a-z]", "");
        return clean.equals("soteria");
    }
}
