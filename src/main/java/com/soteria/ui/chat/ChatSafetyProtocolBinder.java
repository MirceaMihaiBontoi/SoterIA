package com.soteria.ui.chat;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.core.port.KnowledgeBase;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.soteria.ui.view.ChatViewManager;
import com.soteria.ui.view.SoterIAFace;

/**
 * Maps triage protocol state to the safety sidebar and session emergency fields. Must run on the JavaFX thread.
 */
final class ChatSafetyProtocolBinder {

    /**
     * Inputs for {@link #apply(Request)}.
     */
    record Request(
            VBox safetyContainer,
            KnowledgeBase knowledgeBase,
            ChatSession activeSession,
            ChatViewManager viewManager,
            SoterIAFace face,
            String protocolId,
            String status,
            String pillReadyToken,
            String pillAlertToken) {
    }

    private ChatSafetyProtocolBinder() {
    }

    static void apply(Request r) {
        if ("N/A".equals(r.protocolId()) || "RESOLVED".equals(r.status()) || "INACTIVE".equals(r.status())) {
            if ("RESOLVED".equals(r.status())) {
                r.activeSession().setActiveEmergencyId(null);
                r.activeSession().setProtocolLocked(false);
                r.activeSession().getProtocolProgress().clear();
                r.viewManager().setAiStatusPill("Sistema Listo", r.pillReadyToken());
            }
            r.safetyContainer().setVisible(false);
            r.safetyContainer().setManaged(false);
            return;
        }

        Protocol protocol = r.knowledgeBase().getProtocolById(r.protocolId());
        if (protocol == null) {
            return;
        }

        r.safetyContainer().getChildren().clear();
        r.safetyContainer().setVisible(true);
        r.safetyContainer().setManaged(true);

        Label title = new Label(protocol.getTitle().toUpperCase());
        title.getStyleClass().add("safety-title");
        r.safetyContainer().getChildren().add(title);

        for (String step : protocol.getContent().split("\n")) {
            if (step.trim().isEmpty()) continue;
            Label stepLabel = new Label("• " + step.trim());
            stepLabel.getStyleClass().add("safety-step");
            stepLabel.setWrapText(true);
            r.safetyContainer().getChildren().add(stepLabel);
        }

        if ("ACTIVE".equals(r.status())) {
            r.activeSession().setActiveEmergencyId(r.protocolId());
            r.viewManager().setAiStatusPill("Emergencia Activa", r.pillAlertToken());
            r.face().setState(SoterIAFace.State.ALERT);
        }
    }
}
