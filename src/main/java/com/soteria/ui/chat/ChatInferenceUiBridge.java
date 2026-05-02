package com.soteria.ui.chat;

import com.soteria.application.chat.InferenceEngine;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.TTS;
import com.soteria.ui.view.ChatViewManager;
import com.soteria.ui.view.SessionCoordinator;
import com.soteria.ui.view.SoterIAFace;

import javafx.application.Platform;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Implements {@link InferenceEngine.UIUpdateListener} for the main HUD: subtitles, face, bot stream, TTS idle, safety box.
 */
final class ChatInferenceUiBridge implements InferenceEngine.UIUpdateListener {

    private final ChatViewManager viewManager;
    private final SessionCoordinator sessionCoordinator;
    private final SoterIAFace face;
    private final VBox safetyContainer;
    private final KnowledgeBase knowledgeBase;
    private final ChatTTSIdleChain ttsIdleChain;
    private final Logger logger;
    private final String promptReady;
    private final String pillReadyToken;
    private final String pillAlertToken;
    private final Supplier<ChatSession> activeSession;
    private final BooleanSupplier ttsEnabled;
    private final Supplier<TTS> ttsService;

    /** First streaming subtitle chunk of a bot reply opens the bubble; reset when the turn is interrupted. */
    private final AtomicBoolean botMessageStarted = new AtomicBoolean(false);

    ChatInferenceUiBridge(
            ChatViewManager viewManager,
            SessionCoordinator sessionCoordinator,
            SoterIAFace face,
            VBox safetyContainer,
            KnowledgeBase knowledgeBase,
            ChatTTSIdleChain ttsIdleChain,
            Logger logger,
            String promptReady,
            String pillReadyToken,
            String pillAlertToken,
            Supplier<ChatSession> activeSession,
            BooleanSupplier ttsEnabled,
            Supplier<TTS> ttsService) {
        this.viewManager = viewManager;
        this.sessionCoordinator = sessionCoordinator;
        this.face = face;
        this.safetyContainer = safetyContainer;
        this.knowledgeBase = knowledgeBase;
        this.ttsIdleChain = ttsIdleChain;
        this.logger = logger;
        this.promptReady = promptReady;
        this.pillReadyToken = pillReadyToken;
        this.pillAlertToken = pillAlertToken;
        this.activeSession = activeSession;
        this.ttsEnabled = ttsEnabled;
        this.ttsService = ttsService;
    }

    void resetBotStreamState() {
        botMessageStarted.set(false);
    }

    @Override
    public void onSubtitleUpdate(String text) {
        Platform.runLater(() -> {
            if (botMessageStarted.compareAndSet(false, true)) {
                viewManager.startBotMessage();
            }
            viewManager.setSubtitle(text);
            viewManager.updateBotMessage(text);
        });
    }

    @Override
    public void onFaceStateChange(String state) {
        Platform.runLater(() -> face.setState(SoterIAFace.State.valueOf(state)));
    }

    @Override
    public void onResponseFinalized(String finalMessage, String query) {
        Platform.runLater(() -> {
            ChatSession session = activeSession.get();
            session.addMessage(ChatMessage.model(finalMessage));
            session.setTimestamp(System.currentTimeMillis());

            if (session.getMessages().size() <= 2) {
                String title = query.substring(0, Math.min(query.length(), 25));
                if (query.length() > 25) title += "...";
                session.setTitle(title);
            }

            sessionCoordinator.saveCurrentSession();
            viewManager.updateBotMessage(finalMessage);

            TTS tts = ttsService.get();
            if (ttsEnabled.getAsBoolean() && tts != null && tts.isSpeaking()) {
                ttsIdleChain.enqueueAfterSpeechSilence(tts, logger, () -> face.setState(SoterIAFace.State.IDLE));
            } else {
                face.setState(SoterIAFace.State.IDLE);
            }

            viewManager.setSubtitle(promptReady);
        });
    }

    @Override
    public void onSpeakSentence(String sentence, String language) {
        if (ttsEnabled.getAsBoolean()) {
            TTS tts = ttsService.get();
            if (tts != null) {
                tts.setLanguage(language);
                tts.speakQueued(sentence);
            }
        }
    }

    @Override
    public void onSafetyBoxUpdate(String protocolId, String status) {
        Platform.runLater(() -> ChatSafetyProtocolBinder.apply(new ChatSafetyProtocolBinder.Request(
                safetyContainer,
                knowledgeBase,
                activeSession.get(),
                viewManager,
                face,
                protocolId,
                status,
                pillReadyToken,
                pillAlertToken)));
    }
}
