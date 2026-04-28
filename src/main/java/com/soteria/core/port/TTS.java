package com.soteria.core.port;

/**
 * Interface for Text-to-Speech services.
 * Provides speech synthesis capabilities for emergency responses.
 */
public interface TTS {
    /**
     * Speaks the given text.
     * @param text The text to speak
     */
    void speak(String text);

    /**
     * Queues speech without interrupting previous audio.
     * Used for streaming TTS where sentences arrive progressively.
     * Default falls back to speak() for implementations that don't support queuing.
     */
    default void speakQueued(String text) {
        speak(text);
    }

    /**
     * Stops any ongoing speech.
     */
    void stop();

    /**
     * Sets the language to dynamically switch the speaker voice.
     * @param language The language (e.g., "SPANISH", "ENGLISH")
     */
    void setLanguage(String language);

    /**
     * Sets the speech rate (speed).
     * @param rate Speech rate (0.5 = slow, 1.0 = normal, 2.0 = fast)
     */
    void setSpeechRate(float rate);

    /**
     * Sets the speech volume.
     * @param volume Volume level (0.0 = silent, 1.0 = maximum)
     */
    void setVolume(float volume);

    /**
     * Checks if TTS is currently speaking.
     * @return true if speaking, false otherwise
     */
    boolean isSpeaking();

    /**
     * Shuts down the TTS service and releases resources.
     */
    void shutdown();
}
