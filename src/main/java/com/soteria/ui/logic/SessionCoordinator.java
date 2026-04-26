package com.soteria.ui.logic;

import com.soteria.core.domain.chat.ChatSession;
import com.soteria.infrastructure.persistence.ChatSessionRepository;
import javafx.application.Platform;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Manages chat session persistence, history sidebar, and session switching.
 */
public class SessionCoordinator {
    private final VBox sessionList;
    private final VBox historySidebar;
    private final ChatSessionRepository repository = ChatSessionRepository.getInstance();
    private ChatSession activeSession;

    public SessionCoordinator(VBox sessionList, VBox historySidebar) {
        this.sessionList = sessionList;
        this.historySidebar = historySidebar;
    }

    public void setActiveSession(ChatSession session) {
        this.activeSession = session;
    }

    public ChatSession startNewSession() {
        this.activeSession = new ChatSession();
        saveCurrentSession();
        return activeSession;
    }

    public void saveCurrentSession() {
        if (activeSession != null) {
            repository.saveSession(activeSession);
        }
    }

    public void toggleHistorySidebar(Runnable onRefresh) {
        boolean visible = !historySidebar.isVisible();
        historySidebar.setVisible(visible);
        historySidebar.setManaged(visible);
        if (visible) {
            onRefresh.run();
        }
    }

    public void refreshSessionList(ChatSession currentActive, Consumer<ChatSession> onSessionSelected) {
        Platform.runLater(() -> {
            sessionList.getChildren().clear();
            List<ChatSession> sessions = repository.getAllSessions();
            for (ChatSession s : sessions) {
                VBox item = new VBox(2);
                item.getStyleClass().add("session-item");
                if (currentActive != null && s.getId().equals(currentActive.getId())) {
                    item.getStyleClass().add("session-item-selected");
                }

                javafx.scene.control.Label title = new javafx.scene.control.Label(s.getTitle() != null ? s.getTitle() : "Sesión sin título");
                title.getStyleClass().add("session-title");

                java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm");
                java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(s.getTimestamp())
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                javafx.scene.control.Label date = new javafx.scene.control.Label(dt.format(dtf));
                date.getStyleClass().add("session-date");

                item.getChildren().addAll(title, date);
                item.setOnMouseClicked(e -> onSessionSelected.accept(s));
                sessionList.getChildren().add(item);
            }
        });
    }

    public ChatSession getActiveSession() {
        return activeSession;
    }
}
