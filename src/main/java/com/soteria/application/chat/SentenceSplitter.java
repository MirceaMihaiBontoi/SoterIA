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

        int boundary = findFirstSentenceBoundary(text);

        if (boundary != -1) {
            String candidate = text.substring(0, boundary + 1).trim();
            if (!isChunkLongEnoughForTts(candidate, isFinal)) {
                boundary = -1;
            }
        }

        if (boundary == -1 && !isFinal) {
            boundary = softCommaSplit(text);
        }

        if (boundary != -1) {
            return boundary;
        }

        return isFinal ? text.length() - 1 : -1;
    }

    /**
     * Avoid tiny prosodic fragments for Latin; CJK rarely uses spaces so use code-point span instead.
     */
    private static boolean isChunkLongEnoughForTts(String candidate, boolean isFinal) {
        if (isFinal) {
            return true;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int cps = trimmed.codePointCount(0, trimmed.length());
        if (cps >= 6) {
            return true;
        }
        int words = trimmed.split("\\s+").length;
        if (words >= 3) {
            return true;
        }
        int lastCp = trimmed.codePointBefore(trimmed.length());
        return isStrongSentenceEnd(lastCp);
    }

    /** Pause comma / enumeration mark when the segment is already long (code points). */
    private int softCommaSplit(String text) {
        int len = text.codePointCount(0, text.length());
        if (len <= 20) {
            return -1;
        }
        int cpPos = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charLen = Character.charCount(cp);
            if (isPauseComma(cp) && cpPos >= 8) {
                return i + charLen - 1;
            }
            cpPos++;
            i += charLen;
        }
        return -1;
    }

    private int findFirstSentenceBoundary(String text) {
        if (text.isEmpty()) return -1;

        boolean isFirstSentence = (sentenceCount == 0);
        int commaThresholdCp = isFirstSentence ? 12 : 16;
        int runOnCpLimit = isFirstSentence ? 46 : 72;

        int cpIdx = 0;
        int lastEndCharIdx = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charLen = Character.charCount(cp);
            int endCharIdx = i + charLen - 1;

            if (cpIdx >= runOnCpLimit && lastEndCharIdx >= 0) {
                return lastEndCharIdx;
            }

            if (isStrongSentenceEnd(cp)) {
                return endCharIdx;
            }
            if (isPauseComma(cp) && cpIdx >= commaThresholdCp) {
                return endCharIdx;
            }

            lastEndCharIdx = endCharIdx;
            cpIdx++;
            i += charLen;
        }
        return -1;
    }

    private static boolean isStrongSentenceEnd(int cp) {
        return cp == '.' || cp == '!' || cp == '?' || cp == ';' || cp == ':' || cp == '\n'
                || cp == '\u3002' // 。
                || cp == '\uFF01' || cp == '\uFF1F' || cp == '\uFF1B' || cp == '\uFF1A'
                || cp == '\u2026'; // …
    }

    private static boolean isPauseComma(int cp) {
        return cp == ',' || cp == '\uFF0C' || cp == '\u3001';
    }

    public int getSentenceCount() {
        return sentenceCount;
    }

    public void reset() {
        lastTTSSentenceEnd = 0;
        sentenceCount = 0;
    }
}
