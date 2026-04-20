package com.soteria.ui.controller;

import com.soteria.core.model.UserData;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.interfaces.AlertService;
import com.soteria.core.interfaces.HistoryLogger;
import com.soteria.infrastructure.intelligence.RemoteIntelligenceService;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.persistence.EmergencyHistoryPersistence;
import com.soteria.logic.EmergencyDetector;
import com.soteria.ui.MainApp;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Main Controller for the emergency chat interface.
 * Refactored to follow Soteria's clean architecture.
 */
public class MainController implements Initializable {

    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button voiceButton;
    @FXML private Label statusLabel;
    @FXML private Label aiStatusLabel;
    
    private RemoteIntelligenceService aiClient;
    private EmergencyDetector detector;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        aiClient = new RemoteIntelligenceService("http://localhost:8000");
        
        // Temporary placeholder for UserData until MainApp sets it
        UserData dummyUser = new UserData("User", "000", "None", "000");
        detector = new EmergencyDetector(dummyUser, aiClient);
        
        checkAIAvailability();
        
        addBotMessage("Welcome to Soteria. 🚨\n\n" +
                     "I am here to help you in an emergency. Describe what is happening.");
    }

    private void checkAIAvailability() {
        new Thread(() -> {
            boolean isAiAvailable = aiClient.isAvailable();
            Platform.runLater(() -> {
                if (isAiAvailable) {
                    aiStatusLabel.setText("IA: ✅ Connected");
                    aiStatusLabel.setStyle("-fx-text-fill: #10b981;");
                } else {
                    aiStatusLabel.setText("IA: ❌ Disconnected");
                    aiStatusLabel.setStyle("-fx-text-fill: #ef4444;");
                }
            });
        }).start();
    }

    @FXML
    private void handleSendMessage() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;
        
        addUserMessage(message);
        messageInput.clear();
        setStatus("Processing...");
        
        new Thread(() -> {
            EmergencyDetector.DetectionResult result = detector.classifyEmergency(message);
            Platform.runLater(() -> {
                if (result.detected()) {
                    showDetectionResult(result);
                } else {
                    addBotMessage("I could not identify a clear emergency. Please provide more details.");
                }
                setStatus("Ready");
            });
        }).start();
    }

    private void showDetectionResult(EmergencyDetector.DetectionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 **Analysis**\n\n");
        sb.append("Type: ").append(result.typeName()).append("\n");
        sb.append("Context: ").append(result.context()).append("\n\n");
        
        if (result.instructions().length > 0) {
            sb.append("📋 **Instructions:**\n");
            for (String ins : result.instructions()) {
                sb.append("• ").append(ins).append("\n");
            }
        }
        
        addBotMessage(sb.toString());
        addBotMessage("Should I send an alert to emergency services? (Type 'yes' or 'no')");
    }

    private void addUserMessage(String message) {
        HBox box = new HBox(new Text(message));
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(5));
        chatMessages.getChildren().add(box);
        scrollToBottom();
    }

    private void addBotMessage(String message) {
        HBox box = new HBox(new Text(message));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(5));
        chatMessages.getChildren().add(box);
        scrollToBottom();
    }

    private void scrollToBottom() {
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }
}
