package com.soteria.application.chat;

import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.Triage;

import java.util.List;

/**
 * Handles the construction of the RAG context and protocol manifests for the LLM.
 */
public class RAGContextBuilder {

    private final KnowledgeBase knowledgeBase;

    public RAGContextBuilder(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public String prepareContextualQuery(String message, ChatSession session) {
        StringBuilder sb = new StringBuilder();
        if (session.getCategorizedContext() != null) {
            session.getCategorizedContext().values()
                    .forEach(turns -> turns.forEach(t -> sb.append(t).append(" - ")));
        }
        return sb.toString() + message;
    }

    public void applyStickyContext(List<KnowledgeBase.ProtocolMatch> results, ChatSession session) {
        String activeId = session.getActiveEmergencyId();
        if (activeId == null) return;
        
        boolean present = results.stream().anyMatch(r -> r.protocol().getId().equals(activeId));
        if (!present) {
            Protocol p = knowledgeBase.getProtocolById(activeId);
            if (p != null) {
                results.add(0, new KnowledgeBase.ProtocolMatch(p, "PERSISTENT", 1.0f));
            }
        }
    }

    public String buildProtocolManifest(List<KnowledgeBase.ProtocolMatch> results, ChatSession session, Triage.Intent intent) {
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

    public String getEmergencyCategory(Triage.Intent intent) {
        if (intent == null) return "GENERAL";
        return switch (intent) {
            case MEDICAL_EMERGENCY -> "MEDICAL";
            case SECURITY_EMERGENCY -> "SECURITY";
            case ENVIRONMENTAL_EMERGENCY -> "ENVIRONMENTAL";
            case TRAFFIC_EMERGENCY -> "TRAFFIC";
            case GREETING_OR_CASUAL -> "GREETING";
            default -> "GENERAL";
        };
    }
}
