package com.soteria.ui.view;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the UI components and rendering of the chat interface.
 * Handles message bubbles, markdown parsing, and animations.
 */
public class ChatViewManager {
    
    public record UIComponents(
        VBox chatMessages,
        ScrollPane chatScrollPane,
        VBox chatSheet,
        Label subtitleLabel,
        Label partialTranscriptLabel,
        Label aiStatusLabel,
        Circle statusDot
    ) {}

    private static final Logger logger = Logger.getLogger(ChatViewManager.class.getName());
    private final String instanceId = "ChatViewManager-" + UUID.randomUUID().toString().substring(0, 8);

    private static final String CLASS_BOT_BUBBLE = "chat-bubble-bot";
    private static final String CLASS_USER_BUBBLE = "chat-bubble-user";
    private static final String CLASS_THINKING_DOT = "thinking-dot";

    private final UIComponents ui;
    private boolean sheetOpen = false;
    private TextFlow activeBotFlow = null;
    private HBox activeThinkingIndicator = null;

    public ChatViewManager(UIComponents ui) {
        this.ui = ui;
    }

    private void addMessageBubble(String message, String bubbleClass, Pos alignment, Insets padding) {
        logger.log(Level.INFO, "[{0}] Adding bubble to UI. Class: {1}", new Object[]{instanceId, bubbleClass});
        VBox bubble = new VBox();
        bubble.getStyleClass().add(bubbleClass);
        bubble.setMaxWidth(420);

        TextFlow flow = new TextFlow();
        flow.setMaxWidth(400);
        renderMarkdown(message, flow, bubbleClass + "-text");

        bubble.getChildren().add(flow);

        HBox box = new HBox(bubble);
        box.setAlignment(alignment);
        box.setPadding(padding);

        Platform.runLater(() -> {
            ui.chatMessages().getChildren().add(box);
            scrollToBottom();
        });
    }

    public void addUserMessage(String message) {
        addMessageBubble(message, CLASS_USER_BUBBLE, Pos.CENTER_RIGHT, new Insets(5, 0, 5, 50));
    }

    public void addBotMessage(String message) {
        addMessageBubble(message, CLASS_BOT_BUBBLE, Pos.CENTER_LEFT, new Insets(5, 50, 5, 0));
    }

    public void startBotMessage() {
        removeThinkingIndicator();
        VBox bubble = new VBox();
        bubble.getStyleClass().add(CLASS_BOT_BUBBLE);
        bubble.setMaxWidth(420);

        activeBotFlow = new TextFlow();
        activeBotFlow.setMaxWidth(400);
        bubble.getChildren().add(activeBotFlow);

        HBox box = new HBox(bubble);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(5, 50, 5, 0));

        Platform.runLater(() -> {
            logger.info("[" + instanceId + "] Starting bot message bubble in UI.");
            ui.chatMessages().getChildren().add(box);
            scrollToBottom();
        });
    }

    public void updateBotMessage(String text) {
        if (activeBotFlow != null) {
            Platform.runLater(() -> {
                activeBotFlow.getChildren().clear();
                renderMarkdown(text, activeBotFlow, CLASS_BOT_BUBBLE + "-text");
                scrollToBottom();
            });
        }
    }

    public void showThinkingIndicator() {
        if (activeThinkingIndicator != null) return;

        HBox dots = new HBox(5);
        dots.getStyleClass().add("thinking-dots");
        
        for (int i = 0; i < 3; i++) {
            Label dot = new Label("•");
            dot.getStyleClass().add(CLASS_THINKING_DOT);
            dots.getChildren().add(dot);
            
            FadeTransition anim = new FadeTransition(Duration.millis(500), dot);
            anim.setFromValue(0.3);
            anim.setToValue(1.0);
            anim.setCycleCount(Animation.INDEFINITE);
            anim.setAutoReverse(true);
            anim.setDelay(Duration.millis(i * 200.0));
            anim.play();
        }

        VBox bubble = new VBox(dots);
        bubble.getStyleClass().add(CLASS_BOT_BUBBLE);
        bubble.setPadding(new Insets(8, 12, 8, 12));
        bubble.setMaxWidth(60);

        activeThinkingIndicator = new HBox(bubble);
        activeThinkingIndicator.setAlignment(Pos.CENTER_LEFT);
        activeThinkingIndicator.setPadding(new Insets(5, 50, 5, 0));

        Platform.runLater(() -> {
            ui.chatMessages().getChildren().add(activeThinkingIndicator);
            scrollToBottom();
        });
    }

    public void removeThinkingIndicator() {
        if (activeThinkingIndicator != null) {
            Platform.runLater(() -> {
                ui.chatMessages().getChildren().remove(activeThinkingIndicator);
                activeThinkingIndicator = null;
            });
        }
    }

    private void renderMarkdown(String text, TextFlow flow, String styleClass) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().matches("^\\d+\\.\\s.*") || line.trim().startsWith("- ")) {
                Text bullet = new Text("  • ");
                bullet.getStyleClass().add(styleClass);
                bullet.setStyle("-fx-font-weight: bold;");
                flow.getChildren().add(bullet);

                String content = line.trim();
                int prefixEnd = content.startsWith("- ") ? 2 : content.indexOf(". ") + 2;
                parseInline(content.substring(Math.min(prefixEnd, content.length())), flow, styleClass);
            } else {
                parseInline(line, flow, styleClass);
            }

            if (i < lines.length - 1) {
                flow.getChildren().add(new Text("\n"));
            }
        }
    }

    private void parseInline(String text, TextFlow flow, String styleClass) {
        String[] parts = text.split("\\*\\*");
        boolean isBold = text.startsWith("**");

        for (String part : parts) {
            if (part.isEmpty()) {
                isBold = !isBold;
                continue;
            }
            Text segment = new Text(part);
            segment.getStyleClass().add(styleClass);
            if (isBold) {
                segment.setStyle("-fx-font-weight: bold;");
            }
            flow.getChildren().add(segment);
            isBold = !isBold;
        }
    }

    public void scrollToBottom() {
        Platform.runLater(() -> {
            ui.chatScrollPane().layout();
            ui.chatScrollPane().setVvalue(1.0);
        });
    }

    public void setSubtitle(String text) {
        logger.log(Level.INFO, "[{0}] Updating subtitle: {1}", new Object[]{instanceId, text});
        Platform.runLater(() -> ui.subtitleLabel().setText(text));
    }

    public void setPartialTranscript(String text) {
        Platform.runLater(() -> ui.partialTranscriptLabel().setText(text == null ? "" : text));
    }

    public void setAiStatusPill(String text, String dotClass) {
        Platform.runLater(() -> {
            ui.aiStatusLabel().setText(text);
            ui.statusDot().getStyleClass().removeAll("ready", "warming", "offline", "alert");
            ui.statusDot().getStyleClass().add(dotClass);
        });
    }

    public void toggleChatSheet() {
        if (sheetOpen) closeChatSheet();
        else openChatSheet();
    }

    public void openChatSheet() {
        sheetOpen = true;
        ui.chatSheet().setVisible(true);
        ui.chatSheet().setManaged(true);
        double start = ui.chatSheet().getHeight() > 0 ? ui.chatSheet().getHeight() : 520;
        ui.chatSheet().setTranslateY(start);
        ui.chatSheet().setOpacity(0);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(240),
                        new KeyValue(ui.chatSheet().translateYProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(ui.chatSheet().opacityProperty(), 1, Interpolator.EASE_OUT)));
        tl.play();
    }

    public void closeChatSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(220),
                        new KeyValue(ui.chatSheet().translateYProperty(),
                                ui.chatSheet().getHeight() > 0 ? ui.chatSheet().getHeight() : 520,
                                Interpolator.EASE_IN),
                        new KeyValue(ui.chatSheet().opacityProperty(), 0, Interpolator.EASE_IN)));
        tl.setOnFinished(e -> {
            ui.chatSheet().setVisible(false);
            ui.chatSheet().setManaged(false);
        });
        tl.play();
    }

    public void clearMessages() {
        ui.chatMessages().getChildren().clear();
    }

    public boolean isSheetOpen() {
        return sheetOpen;
    }
}
