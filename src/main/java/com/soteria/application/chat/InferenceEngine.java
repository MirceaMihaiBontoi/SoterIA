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
 * Handles the logic for RAG, Triage, and LLM inference.
 * Decoupled from ChatController UI.
 * Restored to match original logic complexity from ChatController.old.java.
 */
public class InferenceEngine {
    private static final Logger logger = Logger.getLogger(InferenceEngine.class.getName());

    private final Triage triageService;
    private final Brain brainService;
    private final KnowledgeBase knowledgeBase;

    public interface UIUpdateListener {
        void onSubtitleUpdate(String text);

        void onFaceStateChange(String state);

        void onResponseFinalized(String finalMessage, String query);

        void onSafetyBoxUpdate(String protocolId, String status);

        /** Called when a complete sentence is ready for TTS during streaming. */
        void onSpeakSentence(String sentence, String language);
    }

    public InferenceEngine(Triage triage, Brain brain, KnowledgeBase kb) {
        this.triageService = triage;
        this.brainService = brain;
        this.knowledgeBase = kb;
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
            // 1. Triage & Classification (using contextual query for better accuracy)
            String contextualQuery = prepareContextualQuery(message, session);
            List<KnowledgeBase.ProtocolMatch> matches = knowledgeBase.findProtocols(contextualQuery,
                    session.getRejectedProtocolIds(), false);
            List<Protocol> candidates = matches.stream().map(KnowledgeBase.ProtocolMatch::protocol).toList();

            Triage.TriageResult triage = triageService.classifyDynamic(contextualQuery, candidates);

            // 2. RAG Context Preparation
            List<KnowledgeBase.ProtocolMatch> results = new ArrayList<>();
            if (triage.isEmergency()) {
                String category = getEmergencyCategory(triage.intent());
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
                applyStickyContext(results, session);
            }
            String context = buildProtocolManifest(results, session, triage.intent());

            // 3. History Filtering (Crucial for context limit management)
            List<ChatMessage> filteredHistory = filterRelevantHistory(session.getMessages(), message, session);

            // 4. Execution
            String initialActiveId = session.getActiveEmergencyId() != null ? session.getActiveEmergencyId() : "N/A";
            brainService.chat(filteredHistory, context, user, language,
                    createBrainCallback(message, session, user, language, listener, attempt, initialActiveId));

        } catch (Exception e) {
            logger.severe("Inference flow failed: " + e.getMessage());
            Platform.runLater(() -> {
                listener.onSubtitleUpdate("Error: Inferencia fallida");
                listener.onFaceStateChange("IDLE");
            });
        }
    }

    private Brain.BrainCallback createBrainCallback(String message, ChatSession session, UserData user,
            String language, UIUpdateListener listener, int attempt, String activeId) {
        return new BrainCallbackHandler(message, session, user, language, listener, attempt, activeId);
    }

    /**
     * Inner class to handle brain callbacks and reduce cognitive complexity.
     */
    private class BrainCallbackHandler implements Brain.BrainCallback {
        private final String message;
        private final ChatSession session;
        private final UserData user;
        private final String language;
        private final UIUpdateListener listener;
        private final int attempt;
        private final StringBuilder responseBuffer = new StringBuilder();
        private boolean dirty = false;
        private int lastTTSSentenceEnd;
        private boolean ttsStarted;
        private int sentenceCount;
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
            this.lastTTSSentenceEnd = 0;
            this.ttsStarted = false;
            this.sentenceCount = 0;
        }

        @Override
        public void onPartialResponse(String token) {
            responseBuffer.append(token);
            String fullUncut = responseBuffer.toString();
            String full = fullUncut.trim();

            // Always update subtitle — no RESPONSE: marker gating
            if (!full.isEmpty()) {
                listener.onSubtitleUpdate(full);
            }

            // Detect sentence boundaries for streaming TTS using un-trimmed text
            checkAndSpeakSentences(fullUncut, false);
        }

        private void checkAndSpeakSentences(String fullText, boolean isFinal) {
            String remaining = fullText.substring(lastTTSSentenceEnd);
            
            while (true) {
                int boundaryIndex = findBestSplitPoint(remaining, isFinal);
                if (boundaryIndex == -1) break;
                
                int absoluteBoundary = lastTTSSentenceEnd + boundaryIndex + 1;
                String sentence = fullText.substring(lastTTSSentenceEnd, absoluteBoundary).trim();
                
                if (!sentence.isEmpty()) {
                    speakSentence(sentence);
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
                // Fragment too short — keep accumulating unless it's the final chunk
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


        private void speakSentence(String sentence) {
            if (!ttsStarted) {
                ttsStarted = true;
                listener.onFaceStateChange("SPEAKING");
            }
            sentenceCount++;
            logger.log(Level.INFO, "TTS Triggered sentence #{0}: [{1}] ({2})", new Object[]{sentenceCount, sentence, this.language});
            listener.onSpeakSentence(sentence, this.language);
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


        @Override
        public void onFinalResponse(String text) {
            String cleaned = cleanResponse(text);

            if (dirty && attempt < 3) {
                logger.info(() -> "Retrying inference after REJECT... Attempt " + (attempt + 1));
                runInferenceFlowInternal(message, session, user, language, listener, attempt + 1);
            } else {
                if (cleaned.isEmpty()) {
                    if (dirty) {
                        cleaned = "Lo siento, no he podido encontrar un protocolo exacto, pero estoy aquí para ayudarte. ¿Puedes darme más detalles?";
                    } else {
                        cleaned = "Entiendo. Estoy analizando la mejor forma de ayudarte.";
                    }
                }
                
                // Finalize any remaining speech using adaptive logic
                this.checkAndSpeakSentences(text, true);
                
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
                    logger.info(() -> "Protocol " + activeId + " rejected by brain. Triggering retry flow.");
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

        private String cleanResponse(String text) {
            return text.trim();
        }
    }

    private void applyStickyContext(List<KnowledgeBase.ProtocolMatch> results, ChatSession session) {
        String activeId = session.getActiveEmergencyId();
        if (activeId == null)
            return;
        boolean present = results.stream().anyMatch(r -> r.protocol().getId().equals(activeId));
        if (!present) {
            Protocol p = knowledgeBase.getProtocolById(activeId);
            if (p != null)
                results.add(0, new KnowledgeBase.ProtocolMatch(p, "PERSISTENT", 1.0f));
        }
    }

    String prepareContextualQuery(String message, ChatSession session) {
        StringBuilder sb = new StringBuilder();
        if (session.getCategorizedContext() != null) {
            session.getCategorizedContext().values()
                    .forEach(turns -> turns.forEach(t -> sb.append(t).append(" - ")));
        }
        return sb.toString() + message;
    }

    private String getEmergencyCategory(Triage.Intent intent) {
        if (intent == null)
            return "GENERAL";
        return switch (intent) {
            case MEDICAL_EMERGENCY -> "MEDICAL";
            case SECURITY_EMERGENCY -> "SECURITY";
            case ENVIRONMENTAL_EMERGENCY -> "ENVIRONMENTAL";
            case TRAFFIC_EMERGENCY -> "TRAFFIC";
            case GREETING_OR_CASUAL -> "GREETING";
            default -> "GENERAL";
        };
    }

    List<ChatMessage> filterRelevantHistory(List<ChatMessage> history, String currentQuery, ChatSession session) {
        final List<ChatMessage> filtered = new ArrayList<>();
        final java.util.Set<String> relevantTurns = new java.util.HashSet<>();

        if (session != null && session.getCategorizedContext() != null) {
            session.getCategorizedContext().values().forEach(relevantTurns::addAll);
        }

        int startCoherence = Math.max(0, history.size() - 4);

        int i = 0;
        while (i < history.size()) {
            ChatMessage msg = history.get(i);
            if (shouldIncludeMessage(i, startCoherence, msg, currentQuery, relevantTurns)) {
                filtered.add(msg);
                // If it's a relevant user message, also add the following model response
                if (isRelevantUserMessage(msg, currentQuery, relevantTurns)
                        && (i + 1 < history.size())
                        && "model".equals(history.get(i + 1).role())) {
                    filtered.add(history.get(i + 1));
                    i++; // Skip the model response
                }
            }
            i++;
        }

        if (filtered.isEmpty()) {
            filtered.add(ChatMessage.user(currentQuery));
        }
        return filtered;
    }

    private boolean shouldIncludeMessage(int index, int coherenceStart, ChatMessage msg,
            String query, java.util.Set<String> relevantTurns) {
        return index >= coherenceStart || isRelevantUserMessage(msg, query, relevantTurns);
    }

    private boolean isRelevantUserMessage(ChatMessage msg, String query, java.util.Set<String> relevantTurns) {
        return "user".equals(msg.role()) && (msg.content().equals(query) || relevantTurns.contains(msg.content()));
    }

    String buildProtocolManifest(List<KnowledgeBase.ProtocolMatch> results, ChatSession session,
            Triage.Intent intent) {
        if (results.isEmpty()) {
            if (intent == Triage.Intent.GREETING_OR_CASUAL) {
                return "PROTOCOL_MANIFEST: greeting or casual";
            }
            return "PROTOCOL_MANIFEST: no protocol matched, ask the user for more info";
        }
        StringBuilder out = new StringBuilder("PROTOCOL_MANIFEST:\n");
        out.append("The following EMERGENCY PROTOCOLS have been retrieved. Use them for ANALYSIS and RESPONSE.\n\n");
        results.forEach(m -> appendProtocolInfo(out, m, session));
        return out.toString();
    }

    private void appendProtocolInfo(StringBuilder out, KnowledgeBase.ProtocolMatch m, ChatSession session) {
        Protocol p = m.protocol();
        boolean isLocked = (session != null && session.isProtocolLocked()
                && p.getId().equals(session.getActiveEmergencyId()));
        List<String> steps = p.getSteps();
        int total = (steps != null) ? steps.size() : 0;

        out.append("- Source-Ref: ").append(p.getId())
                .append(" | Situation: ").append(p.getTitle())
                .append(" | State: ").append(isLocked ? "LOCKED" : "UNLOCKED")
                .append(" | Steps-Count: ").append(total).append("\n");

        if (total > 0) {
            String req = (session != null) ? session.getRequestedStepsMap().getOrDefault(p.getId(), "1") : "1-10";
            out.append("  | REQUESTED_STEPS (").append(req).append("):\n");
            renderSteps(out, steps, req);
        } else {
            out.append("  | STEPS: No instructions available.\n");
        }
        out.append("\n");
    }

    private void renderSteps(StringBuilder out, List<String> steps, String req) {
        try {
            String clean = req.toUpperCase().replace("STEP", "").trim();
            if (clean.contains("-")) {
                String[] parts = clean.split("-");
                int s = Integer.parseInt(parts[0].trim());
                int e = Integer.parseInt(parts[1].trim());
                for (int i = s; i <= e; i++) {
                    if (i >= 1 && i <= steps.size()) {
                        out.append("    Step ").append(i).append(": ").append(steps.get(i - 1)).append("\n");
                    }
                }
            } else {
                int n = Integer.parseInt(clean);
                if (n >= 1 && n <= steps.size()) {
                    out.append("    Step ").append(n).append(": ").append(steps.get(n - 1)).append("\n");
                }
            }
        } catch (Exception _) {
            if (steps != null && !steps.isEmpty()) {
                out.append("    Step 1: ").append(steps.get(0)).append("\n");
            }
        }
    }

    public void cancel() {
        if (this.brainService != null) {
            this.brainService.cancel();
        }
    }
}
