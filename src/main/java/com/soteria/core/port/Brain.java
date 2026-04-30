package com.soteria.core.port;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.model.UserData;

import java.util.List;

public interface Brain {
    interface BrainCallback {
        void onPartialResponse(String text);

        void onFinalResponse(String text);

        void onStatusUpdate(String protocolId, String status);

        void onCommand(String type, String value);
    }

    void chat(List<ChatMessage> history, String context, UserData profile, String language, BrainCallback callback);

    void cancel();
}
