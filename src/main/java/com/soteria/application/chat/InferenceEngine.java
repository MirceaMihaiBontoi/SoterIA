package com.soteria.application.chat;

import com.soteria.core.port.Brain;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.Triage;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.model.UserData;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates the flow for RAG, Triage, and LLM inference.
 * Delegates specialized logic to ContextBuilder, HistoryManager, and SentenceSplitter.
 */
public class InferenceEngine {
    private static final Logger logger = Logger.getLogger(InferenceEngine.class.getName());

    private final Triage triageService;
    private final Brain brainService;
    private final KnowledgeBase knowledgeBase;
    
    // Modular components
    private final RAGContextBuilder contextBuilder;
    private final HistoryManager historyManager;

    public interface UIUpdateListener {
        void onSubtitleUpdate(String text);
        void onFaceStateChange(String state);
        void onResponseFinalized(String finalMessage, String query);
        void onSafetyBoxUpdate(String protocolId, String status);
        void onSpeakSentence(String sentence, String language);
    }

    public InferenceEngine(Triage triage, Brain brain, KnowledgeBase kb) {
        this.triageService = triage;
        this.brainService = brain;
        this.knowledgeBase = kb;
        this.contextBuilder = new RAGContextBuilder(kb);
        this.historyManager = new HistoryManager();
    }

    public void runInference(String message, ChatSession session, UserData user, String language,
            UIUpdateListener listener) {
        runInferenceFlowInternal(message, session, user, language, listener, 1);
    }

    private void runInferenceFlowInternal(String message, ChatSession session, UserData user, String language,
            UIUpdateListener listener, int attempt) {
        if (attempt > 3) {
            logger.warning("Max inference attempts reached (3). Breaking loop.");
            return;
        }

        try {
            // 1. Triage & Classification
            String contextualQuery = contextBuilder.prepareContextualQuery(message, session);
            List<KnowledgeBase.ProtocolMatch> matches = knowledgeBase.findProtocols(contextualQuery,
                    session.getRejectedProtocolIds(), false);
            List<Protocol> candidates = matches.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();

            Triage.TriageResult triage = triageService.classifyDynamic(contextualQuery, candidates);

            // 2. RAG Context Preparation
            List<KnowledgeBase.ProtocolMatch> results = new ArrayList<>();
            if (triage.isEmergency()) {
                String category = contextBuilder.getEmergencyCategory(triage.intent());
                session.getCategorizedContext()
                        .computeIfAbsent(category, k -> new ArrayList<>())
                        .add(message);
                session.getActiveCategories().add(category);

                if (triage.protocol() != null) {
                    results.add(new KnowledgeBase.ProtocolMatch(triage.protocol(), "CLASSIFIER", triage.score()));
                    if (session.getActiveEmergencyId() == null) {
                        session.setActiveEmergencyId(triage.protocol().getId());
                        session.setProtocolLocked(true);
                    }
                }
                contextBuilder.applyStickyContext(results, session);
            }
            String context = contextBuilder.buildProtocolManifest(results, session, triage.intent());

            // 3. History Filtering
            List<ChatMessage> filteredHistory = historyManager.filterRelevantHistory(session.getMessages(), message, session);

            // 4. Execution
            String initialActiveId = session.getActiveEmergencyId() != null ? session.getActiveEmergencyId() : "N/A";
            brainService.chat(filteredHistory, context, user, language,
                    new BrainCallbackHandler(message, session, user, language, listener, attempt, initialActiveId));

        } catch (Exception e) {
            logger.severe("Inference flow failed: " + e.getMessage());
            Platform.runLater(() -> {
                listener.onSubtitleUpdate("Error: Inferencia fallida");
                listener.onFaceStateChange("IDLE");
            });
        }
    }

    private class BrainCallbackHandler implements Brain.BrainCallback {
        private final String message;
        private final ChatSession session;
        private final UserData user;
        private final String language;
        private final UIUpdateListener listener;
        private final int attempt;
        private final StringBuilder responseBuffer = new StringBuilder();
        private final SentenceSplitter splitter = new SentenceSplitter();
        private boolean dirty = false;
        private boolean ttsStarted = false;
        private String currentActiveId;

        BrainCallbackHandler(String message, ChatSession session, UserData user, String language,
                UIUpdateListener listener, int attempt, String activeId) {
            this.message = message;
            this.session = session;
            this.user = user;
            this.language = language;
            this.listener = listener;
            this.attempt = attempt;
            this.currentActiveId = activeId;
        }

        @Override
        public void onPartialResponse(String token) {
            responseBuffer.append(token);
            String fullText = responseBuffer.toString();
            
            if (!fullText.trim().isEmpty()) {
                listener.onSubtitleUpdate(fullText.trim());
            }

            splitter.process(fullText, false, sentence -> {
                if (!ttsStarted) {
                    ttsStarted = true;
                    listener.onFaceStateChange("SPEAKING");
                }
                logger.log(Level.INFO, "TTS Streaming Sentence #{0}: [{1}]", 
                        new Object[]{splitter.getSentenceCount(), sentence});
                listener.onSpeakSentence(sentence, this.language);
            });
        }

        @Override
        public void onFinalResponse(String text) {
            if (dirty && attempt < 3) {
                logger.info(() -> "Retrying inference after REJECT... Attempt " + (attempt + 1));
                runInferenceFlowInternal(message, session, user, language, listener, attempt + 1);
            } else {
                String cleaned = text.trim();
                if (cleaned.isEmpty()) {
                    cleaned = dirty ? "Lo siento, no he podido encontrar un protocolo exacto..." : "Entiendo...";
                }
                
                // Finalize remaining audio
                splitter.process(text, true, sentence -> listener.onSpeakSentence(sentence, this.language));
                listener.onResponseFinalized(cleaned, message);
            }
        }

        @Override
        public void onStatusUpdate(String protocolId, String status) {
            if ("REJECT".equals(protocolId)) {
                String activeId = session.getActiveEmergencyId();
                if (activeId != null && !"N/A".equals(activeId)) {
                    session.addRejectedProtocolId(activeId);
                    session.setActiveEmergencyId(null);
                    session.setProtocolLocked(false);
                }
                dirty = true;
                return;
            }

            if (protocolId != null && !"N/A".equals(protocolId) && !protocolId.equals(currentActiveId)) {
                this.currentActiveId = protocolId;
                session.setActiveEmergencyId(protocolId);
            }
            listener.onSafetyBoxUpdate(currentActiveId, status);
        }

        @Override
        public void onCommand(String type, String value) {
            if ("STEP".equals(type)) {
                session.getRequestedStepsMap().put(currentActiveId, value);
                dirty = true;
            }
        }
    }

    public void cancel() {
        if (this.brainService != null) {
            this.brainService.cancel();
        }
    }
}
