package com.soteria.application.chat;

/**
 * Logic for splitting real-time LLM token stream into natural sentences for TTS.
 */
public class SentenceSplitter {

    public interface SentenceListener {
        void onSentenceReady(String sentence);
    }

    private int lastTTSSentenceEnd = 0;
    private int sentenceCount = 0;

    public void process(String fullText, boolean isFinal, SentenceListener listener) {
        String remaining = fullText.substring(lastTTSSentenceEnd);
        
        while (true) {
            int boundaryIndex = findBestSplitPoint(remaining, isFinal);
            if (boundaryIndex == -1) break;
            
            int absoluteBoundary = lastTTSSentenceEnd + boundaryIndex + 1;
            String sentence = fullText.substring(lastTTSSentenceEnd, absoluteBoundary).trim();
            
            if (!sentence.isEmpty()) {
                sentenceCount++;
                listener.onSentenceReady(sentence);
                lastTTSSentenceEnd = absoluteBoundary;
            }
            
            remaining = fullText.substring(lastTTSSentenceEnd);
        }
    }

    private int findBestSplitPoint(String text, boolean isFinal) {
        if (text.isEmpty()) return -1;
        
        // 1. Check for sentence boundaries (. ! ? : ; ...)
        int boundary = findFirstSentenceBoundary(text);
        
        if (boundary != -1) {
            // Reject splits that produce fragments too short for natural prosody
            int wordCount = text.substring(0, boundary + 1).trim().split("\\s+").length;
            if (wordCount >= 3 || isFinal) {
                return boundary;
            }
        }
        
        // 2. If no sentence boundary but text is getting long, split at comma
        if (!isFinal && text.split("\\s+").length > 5) {
            int commaIndex = text.indexOf(',');
            if (commaIndex != -1 && commaIndex > 2) {
                return commaIndex;
            }
        }
        
        // 3. If final, return everything remaining
        return isFinal ? text.length() - 1 : -1;
    }

    private int findFirstSentenceBoundary(String text) {
        if (text == null || text.isEmpty()) return -1;

        int wordCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) wordCount++;
            if (isStrongBoundary(c)) return i;
            if (shouldForceSplit(c, wordCount, i)) return i;
        }
        return -1;
    }

    private boolean isStrongBoundary(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\n' || c == ';' || c == ':';
    }

    private boolean shouldForceSplit(char c, int wordCount, int index) {
        // For the first sentence, split slightly earlier so TTS starts sooner
        boolean isFirstSentence = (sentenceCount == 0);
        int commaThreshold = isFirstSentence ? 3 : 4;
        int runOnWordLimit = isFirstSentence ? 6 : 10;
        int runOnCharLimit = isFirstSentence ? 45 : 70;
        
        if (c == ',' && wordCount >= commaThreshold) return true;
        return wordCount >= runOnWordLimit || index >= runOnCharLimit;
    }

    public int getSentenceCount() {
        return sentenceCount;
    }
    
    public void reset() {
        lastTTSSentenceEnd = 0;
        sentenceCount = 0;
    }
}
