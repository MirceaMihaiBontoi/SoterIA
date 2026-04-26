package com.soteria.core.port;

/**
 * Interface for receiving STT events.
 */
public interface STTListener {
    void onResult(String text);
    void onPartialResult(String text);
    void onError(Throwable t);
}
