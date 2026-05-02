package com.soteria.ui.view;

import com.soteria.core.domain.chat.ChatSession;
import com.soteria.infrastructure.persistence.ChatSessionRepository;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

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

    /** Closes the drawer without toggling (e.g. before opening another full-screen overlay). */
    public void closeHistorySidebar() {
        historySidebar.setVisible(false);
        historySidebar.setManaged(false);
    }

    public void refreshSessionList(ChatSession currentActive, Consumer<ChatSession> onSessionSelected,
            Consumer<ChatSession> onSessionDeleted) {
        Platform.runLater(() -> {
            sessionList.getChildren().clear();
            List<ChatSession> sessions = repository.getAllSessions();
            for (ChatSession s : sessions) {
                HBox row = new HBox(6);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("session-item");
                if (currentActive != null && s.getId().equals(currentActive.getId())) {
                    row.getStyleClass().add("session-item-selected");
                }

                VBox textCol = new VBox(2);
                HBox.setHgrow(textCol, Priority.ALWAYS);

                Label title = new Label(s.getTitle() != null ? s.getTitle() : "Sesión sin título");
                title.getStyleClass().add("session-title");
                title.setMaxWidth(Double.MAX_VALUE);

                java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm");
                java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(s.getTimestamp())
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                Label date = new Label(dt.format(dtf));
                date.getStyleClass().add("session-date");

                textCol.getChildren().addAll(title, date);
                textCol.setOnMouseClicked(e -> onSessionSelected.accept(s));

                Button deleteBtn = new Button();
                deleteBtn.getStyleClass().add("session-delete-button");
                deleteBtn.setFocusTraversable(false);
                FontIcon trashIcon = new FontIcon();
                trashIcon.setIconLiteral("mdal-delete_outline");
                trashIcon.setIconSize(18);
                trashIcon.getStyleClass().add("session-delete-icon");
                deleteBtn.setGraphic(trashIcon);
                deleteBtn.setOnAction(e -> {
                    e.consume();
                    repository.delete(s.getId());
                    onSessionDeleted.accept(s);
                });

                row.getChildren().addAll(textCol, deleteBtn);
                sessionList.getChildren().add(row);
            }
        });
    }

    public ChatSession getActiveSession() {
        return activeSession;
    }
}
