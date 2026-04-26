package com.soteria.core.port;

public interface STT {
    void startListening(STTListener listener);

    void stopListening();
}
