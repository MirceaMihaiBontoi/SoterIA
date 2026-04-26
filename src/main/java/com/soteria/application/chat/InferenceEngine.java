package com.soteria.application.chat;

import com.soteria.infrastructure.intelligence.knowledge.*;
import com.soteria.infrastructure.intelligence.triage.*;
import com.soteria.infrastructure.intelligence.llm.*;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.model.UserData;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles the logic for RAG, Triage, and LLM inference.
 * Decoupled from ChatController UI.
 * Restored to match original logic complexity from ChatController.old.java.
 */
public class InferenceEngine {
    private static final Logger logger = Logger.getLogger(InferenceEngine.class.getName());
    private static final String RESPONSE_MARKER = "RESPONSE:";

    private final TriageService triageService;
    private final LocalBrainService brainService;
    private final EmergencyKnowledgeBase knowledgeBase;

    public interface UIUpdateListener {
        void onSubtitleUpdate(String text);
        void onFaceStateChange(String state);
        void onResponseFinalized(String finalMessage, String query);
        void onSafetyBoxUpdate(String protocolId, String status);
    }

    public InferenceEngine(TriageService triage, LocalBrainService brain, EmergencyKnowledgeBase kb) {
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
            List<EmergencyKnowledgeBase.ProtocolMatch> matches = knowledgeBase.findProtocols(contextualQuery,
                    session.getRejectedProtocolIds(), false);
            List<Protocol> candidates = matches.stream().map(EmergencyKnowledgeBase.ProtocolMatch::protocol).toList();

            TriageService.TriageResult triage = triageService.classifyDynamic(contextualQuery, candidates);

            // 2. RAG Context Preparation & Optimization
            String context;
            if (!triage.isEmergency() && !isEmergencyCommand(message) && attempt <= 1) {
                context = "This is a casual conversation or greeting. No medical protocols needed. Be friendly but keep it short.";
            } else {
                // Update session state with triage findings (Logic restored from ChatController.old.java)
                if (triage.isEmergency()) {
                    String category = getEmergencyCategory(triage.intent());
                    session.getCategorizedContext()
                            .computeIfAbsent(category, k -> new ArrayList<>())
                            .add(message);
                    session.getActiveCategories().add(category);
                }

                List<EmergencyKnowledgeBase.ProtocolMatch> results = new ArrayList<>();
                if (triage.protocol() != null) {
                    results.add(new EmergencyKnowledgeBase.ProtocolMatch(triage.protocol(), "CLASSIFIER", triage.score()));
                    // Auto-lock the protocol if the classifier is confident
                    if (session.getActiveEmergencyId() == null) {
                        session.setActiveEmergencyId(triage.protocol().getId());
                        session.setProtocolLocked(true);
                    }
                }
                applyStickyContext(results, session);
                context = buildProtocolManifest(results, session);
            }

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

    private LocalBrainService.BrainCallback createBrainCallback(String message, ChatSession session, UserData user,
            String language, UIUpdateListener listener, int attempt, String activeId) {
        return new BrainCallbackHandler(message, session, user, language, listener, attempt, activeId);
    }

    /**
     * Inner class to handle brain callbacks and reduce cognitive complexity.
     */
    private class BrainCallbackHandler implements LocalBrainService.BrainCallback {
        private final String message;
        private final ChatSession session;
        private final UserData user;
        private final String language;
        private final UIUpdateListener listener;
        private final int attempt;
        private final StringBuilder responseBuffer = new StringBuilder();
        private boolean dirty = false;
        private boolean isResponseStarted = false;
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
            String full = responseBuffer.toString();
            handlePartialResponse(full);
        }

        private void handlePartialResponse(String full) {
            int respIdx = full.toUpperCase().indexOf(RESPONSE_MARKER);
            if (!isResponseStarted) {
                if (respIdx != -1) {
                    isResponseStarted = true;
                    String actualContent = full.substring(respIdx + RESPONSE_MARKER.length());
                    listener.onSubtitleUpdate(cleanResponse(actualContent));
                }
            } else {
                String actualContent = (respIdx != -1) ? full.substring(respIdx + RESPONSE_MARKER.length()) : full;
                listener.onSubtitleUpdate(cleanResponse(actualContent));
            }
        }

        @Override
        public void onFinalResponse(String text) {
            if (dirty) {
                runInferenceFlowInternal(message, session, user, language, listener, attempt + 1);
            } else {
                int respIdx = text.toUpperCase().indexOf(RESPONSE_MARKER);
                String finalContent = (respIdx != -1) ? text.substring(respIdx + RESPONSE_MARKER.length()) : text;
                String cleaned = cleanResponse(finalContent);
                
                // Restore fallback logic from ChatController.old.java
                if (cleaned.isEmpty()) {
                    cleaned = "Entiendo. Estoy analizando la mejor forma de ayudarte.";
                }
                
                listener.onResponseFinalized(cleaned, message);
            }
        }

        @Override
        public void onStatusUpdate(String protocolId, String status) {
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
            // Restore surgical cleaning from ChatController.old.java
            // Only remove tags/signals, not the content following them
            return text.replaceAll("(?i)(PROTOCOL|STEP|STATE|STATUS|RESOLVED|ANALISIS|RESPONSE):", "")
                       .replace("STOP IMMEDIATELY", "")
                       .replaceAll("\\s+", " ")
                       .trim();
        }
    }

    private void applyStickyContext(List<EmergencyKnowledgeBase.ProtocolMatch> results, ChatSession session) {
        String activeId = session.getActiveEmergencyId();
        if (activeId == null)
            return;
        boolean present = results.stream().anyMatch(r -> r.protocol().getId().equals(activeId));
        if (!present) {
            Protocol p = knowledgeBase.getProtocolById(activeId);
            if (p != null)
                results.add(0, new EmergencyKnowledgeBase.ProtocolMatch(p, "PERSISTENT", 1.0f));
        }
    }

    private String prepareContextualQuery(String message, ChatSession session) {
        StringBuilder sb = new StringBuilder();
        if (session.getCategorizedContext() != null) {
            session.getCategorizedContext().values()
                    .forEach(turns -> turns.forEach(t -> sb.append(t).append(" - ")));
        }
        return sb.toString() + message;
    }



    public boolean isEmergencyCommand(String message) {
        String msg = message.toLowerCase();
        return msg.contains("112") || msg.contains("911") || msg.contains("alert") || msg.contains("emergency")
                || msg.contains("help") || msg.contains("ayuda") || msg.contains("sos") 
                || msg.contains("ambulance") || msg.contains("ambulancia");
    }

    private String getEmergencyCategory(TriageService.Intent intent) {
        if (intent == null) return "GENERAL";
        return switch (intent) {
            case MEDICAL_EMERGENCY -> "MEDICAL";
            case SECURITY_EMERGENCY -> "SECURITY";
            case ENVIRONMENTAL_EMERGENCY -> "ENVIRONMENTAL";
            case TRAFFIC_EMERGENCY -> "TRAFFIC";
            default -> "GENERAL";
        };
    }

    private List<ChatMessage> filterRelevantHistory(List<ChatMessage> history, String currentQuery, ChatSession session) {
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

    public String buildProtocolManifest(List<EmergencyKnowledgeBase.ProtocolMatch> results, ChatSession session) {
        if (results.isEmpty())
            return "No specific protocol matched.";
        StringBuilder out = new StringBuilder("PROTOCOL_MANIFEST:\n");
        out.append("The following EMERGENCY PROTOCOLS have been retrieved. Use them for ANALYSIS and RESPONSE.\n\n");
        results.forEach(m -> appendProtocolInfo(out, m, session));
        return out.toString();
    }

    private void appendProtocolInfo(StringBuilder out, EmergencyKnowledgeBase.ProtocolMatch m, ChatSession session) {
        Protocol p = m.protocol();
        boolean isLocked = (session != null && session.isProtocolLocked()
                && p.getId().equals(session.getActiveEmergencyId()));
        List<String> steps = p.getSteps();
        int total = (steps != null) ? steps.size() : 0;

        out.append("- ID: ").append(p.getId())
                .append(" | Title: ").append(p.getTitle())
                .append(" | STATUS: ").append(isLocked ? "LOCKED" : "UNLOCKED")
                .append(" | TOTAL_STEPS: ").append(total).append("\n");

        if (total > 0) {
            String req = (session != null) ? session.getRequestedStepsMap().getOrDefault(p.getId(), "1") : "1";
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
}
