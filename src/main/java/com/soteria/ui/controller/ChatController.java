package com.soteria.ui.controller;

import com.soteria.core.interfaces.AlertService;
import com.soteria.core.interfaces.LocationProvider;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.intelligence.ChatMessage;
import com.soteria.infrastructure.intelligence.LocalBrainService;
import com.soteria.infrastructure.intelligence.MedicalKnowledgeBase;
import com.soteria.infrastructure.intelligence.Protocol;
import com.soteria.infrastructure.intelligence.STTListener;
import com.soteria.infrastructure.intelligence.VoskSTTService;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;

public class ChatController {

    private static final String STATUS_READY = "Ready";
    private static final int MAX_HISTORY_TURNS = 10;

    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button voiceButton;
    @FXML private Label aiStatusLabel;
    @FXML private Label statusLabel;

    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();

    private UserData currentUser;

    private VoskSTTService sttService;
    private LocalBrainService brainService;
    private MedicalKnowledgeBase knowledgeBase;

    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private String currentLanguage = "Spanish";

    public void init(UserData profile, BootstrapService bootstrap) {
        this.currentUser = profile;

        addBotMessage("Hi " + profile.fullName() + ". I am Soteria, your emergency assistant. "
                + "Describe what is happening, or press the microphone.");

        aiStatusLabel.setText("AI: warming up...");
        setStatus("Preparing...");

        bootstrap.ready().whenComplete((ok, err) -> Platform.runLater(() -> {
            if (err != null) {
                aiStatusLabel.setText("AI: error");
                setStatus("Init failed");
                return;
            }
            this.sttService = bootstrap.sttService();
            this.brainService = bootstrap.brainService();
            this.knowledgeBase = bootstrap.knowledgeBase();
            this.aiAvailable = true;
            aiStatusLabel.setText("AI: ready (" + bootstrap.capability().getRecommendedProfile() + ")");
            setStatus(STATUS_READY);
        }));
    }

    @FXML
    private void handleSendMessage() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;
        addUserMessage(message);
        messageInput.clear();
        setStatus("Processing...");
        processMessage(message);
    }

    @FXML
    private void handleVoiceInput() {
        if (!aiAvailable) {
            addBotMessage("System is still initializing. Please wait.");
            return;
        }

        if (!isRecording) {
            isRecording = true;
            voiceButton.setText("⏹");
            setStatus("Listening...");
            sttService.startListening(new STTListener() {
                @Override public void onResult(String text) {
                    if (!text.isEmpty()) {
                        Platform.runLater(() -> {
                            addUserMessage(text);
                            processMessage(text);
                        });
                    }
                }
                @Override public void onPartialResult(String text) {
                    Platform.runLater(() -> setStatus("… " + text));
                }
                @Override public void onError(Throwable t) {
                    Platform.runLater(() -> setStatus("Mic error: " + t.getMessage()));
                    stopRecordingUI();
                }
            });
        } else {
            stopRecordingUI();
            sttService.stopListening();
        }
    }

    private void stopRecordingUI() {
        isRecording = false;
        voiceButton.setText("🎤");
        setStatus(STATUS_READY);
    }

    private void processMessage(String message) {
        if (isEmergencyCommand(message)) {
            handleEmergencyAlert(message);
            return;
        }
        if (!aiAvailable) return;

        conversationHistory.add(ChatMessage.user(message));
        trimHistory();

        setStatus("AI thinking...");
        List<ChatMessage> snapshot = List.copyOf(conversationHistory);
        new Thread(() -> {
            List<Protocol> results = knowledgeBase.findProtocols(message);
            String context = results.isEmpty() ? "No specific protocol matched." : results.get(0).getContent();
            String response = brainService.generateResponse(snapshot, context, currentLanguage);
            Platform.runLater(() -> {
                conversationHistory.add(ChatMessage.model(response));
                trimHistory();
                addBotMessage(response);
                setStatus(STATUS_READY);
            });
        }, "soteria-inference").start();
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_TURNS) {
            conversationHistory.remove(0);
        }
        // Gemma template requires the conversation to start with a user turn.
        while (!conversationHistory.isEmpty() && !"user".equals(conversationHistory.get(0).role())) {
            conversationHistory.remove(0);
        }
    }

    private void addUserMessage(String message) {
        addBubble(message, "chat-bubble-user", Pos.CENTER_RIGHT, new Insets(5, 0, 5, 50));
    }

    private void addBotMessage(String message) {
        addBubble(message, "chat-bubble-bot", Pos.CENTER_LEFT, new Insets(5, 50, 5, 0));
    }

    private void addBubble(String message, String bubbleClass, Pos alignment, Insets padding) {
        HBox box = new HBox();
        box.setAlignment(alignment);
        box.setPadding(padding);

        VBox bubble = new VBox();
        bubble.getStyleClass().add(bubbleClass);

        Text text = new Text(message);
        text.getStyleClass().add(bubbleClass + "-text");
        text.setWrappingWidth(420);

        TextFlow flow = new TextFlow(text);
        bubble.getChildren().add(flow);
        box.getChildren().add(bubble);
        chatMessages.getChildren().add(box);
        scrollToBottom();
    }

    private void scrollToBottom() {
        Platform.runLater(() -> {
            chatScrollPane.layout();
            chatScrollPane.setVvalue(1.0);
        });
    }

    private void setStatus(String status) {
        Platform.runLater(() -> statusLabel.setText(status));
    }

    private boolean isEmergencyCommand(String message) {
        String msg = message.toLowerCase();
        return msg.contains("112") || msg.contains("alert") || msg.contains("emergency")
                || msg.contains("help") || msg.contains("ambulance");
    }

    private void handleEmergencyAlert(String message) {
        setStatus("Sending alert...");
        new Thread(() -> {
            try {
                String location = locationProvider.getLocationDescription();
                EmergencyEvent event = new EmergencyEvent(
                        "CHAT EMERGENCY: " + message,
                        location,
                        10,
                        currentUser != null ? currentUser.fullName() : "Unknown");
                boolean success = alertService.send(event);
                Platform.runLater(() -> {
                    if (success) {
                        addBotMessage("Emergency alert sent. Detected location: " + location
                                + ". Professional help is on the way — stay calm.");
                        setStatus("Active alert");
                    } else {
                        addBotMessage("Automated alert failed. Please call 112/911 immediately.");
                        setStatus("Error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("Error"));
            }
        }, "soteria-alert").start();
    }
}
