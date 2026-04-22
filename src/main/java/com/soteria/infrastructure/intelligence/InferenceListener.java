package com.soteria.infrastructure.intelligence;

/**
 * Listener for streaming AI inference responses.
 * Allows the orchestrator to react to metadata headers before the full response is finished.
 */
public interface InferenceListener {
    /**
     * Called when a new chunk of text is generated.
     */
    void onToken(String token);

    /**
     * Called when the [ANALYSIS] block has been fully parsed.
     * @param protocolId The ID of the matched protocol (e.g., FIRE_001)
     * @param status The detected emergency status (TRIAGE, ACTIVE, RESOLVED)
     */
    void onAnalysisComplete(String protocolId, String status);

    /**
     * Called when the entire inference turn is finished.
     * @param fullText The complete conversational response (excluding metadata header)
     */
    void onComplete(String fullText);

    /**
     * Called if an error occurs during inference.
     */
    void onError(Throwable t);
}
