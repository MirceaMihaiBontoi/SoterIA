package com.soteria.infrastructure.intelligence.system;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Contextual Voice Activity Detection wrapper that uses temporal context
 * to reduce false positives. Instead of deciding frame-by-frame, it looks
 * at a sliding window of recent decisions.
 * 
 * This is how professional systems avoid spurious VAD triggers from
 * transient noise (door slams, keyboard clicks, etc.).
 */
public class ContextualVAD {
    
    private static final int CONTEXT_WINDOW_SIZE = 3;
    private static final int MIN_SPEECH_FRAMES = 2; // 2 out of 3 must be speech
    
    private final Deque<Boolean> recentDecisions = new ArrayDeque<>(CONTEXT_WINDOW_SIZE);
    
    /**
     * Adds a new VAD decision and returns the contextual decision.
     * 
     * @param isSpeech raw VAD decision for current frame
     * @return contextual decision based on recent history
     */
    public boolean addDecision(boolean isSpeech) {
        // Add new decision
        recentDecisions.addLast(isSpeech);
        
        // Keep window size bounded
        if (recentDecisions.size() > CONTEXT_WINDOW_SIZE) {
            recentDecisions.removeFirst();
        }
        
        // Need full window before making contextual decisions
        if (recentDecisions.size() < CONTEXT_WINDOW_SIZE) {
            return isSpeech; // Not enough context yet
        }
        
        // Count how many recent frames were speech
        int speechCount = 0;
        for (boolean decision : recentDecisions) {
            if (decision) speechCount++;
        }
        
        // Majority vote: if 2+ out of 3 frames are speech, it's speech
        return speechCount >= MIN_SPEECH_FRAMES;
    }
    
    /**
     * Resets the context window (call when starting a new session).
     */
    public void reset() {
        recentDecisions.clear();
    }
    
    /**
     * Returns true if we have enough context to make reliable decisions.
     */
    public boolean hasFullContext() {
        return recentDecisions.size() >= CONTEXT_WINDOW_SIZE;
    }
}
