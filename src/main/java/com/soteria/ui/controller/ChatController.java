package com.soteria.ui.controller;

import com.soteria.core.model.UserData;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.interfaces.AlertService;
import com.soteria.core.interfaces.LocationProvider;
import com.soteria.infrastructure.intelligence.*;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;

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
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Controller for the conversational chat interface.
 */
public class ChatController implements Initializable {

    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button voiceButton;
    @FXML private Label aiStatusLabel;
    @FXML private Label statusLabel;
    
    private static final String STATUS_READY = "Ready";
    
    // Phase 3 Native Services
    private SystemCapability systemCapability;
    private ModelManager modelManager;
    private VoskSTTService sttService;
    private LocalBrainService brainService;
    private MedicalKnowledgeBase knowledgeBase;

    private UserData currentUser;
    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private String currentLanguage = "Spanish"; // Placeholder for Phase 5 UI Selection

    // Conversation history passed to the local brain on every turn.
    // Alternates user/model. Capped to avoid blowing the 2048-token context.
    private static final int MAX_HISTORY_TURNS = 10;
    private final List<ChatMessage> conversationHistory = new ArrayList<>();

    private LocationProvider locationProvider;
    private AlertService alertService;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        locationProvider = new SystemGPSLocation();
        alertService = new NotificationAlertService();
        
        initializeNativeIntelligence();
        voiceButton.setOnAction(event -> handleVoiceInput());
    }

    private void initializeNativeIntelligence() {
        new Thread(() -> {
            try {
                Platform.runLater(() -> setStatus("Initializing System..."));
                systemCapability = new SystemCapability();
                modelManager = new ModelManager(systemCapability);
                knowledgeBase = new MedicalKnowledgeBase();

                // 1. Download Vosk ( Hearing )
                if (!modelManager.isVoskModelReady(currentLanguage)) {
                    Platform.runLater(() -> {
                        aiStatusLabel.setText("Soteria: ⏳ Downloading Hearing (" + currentLanguage + ")...");
                        aiStatusLabel.setStyle("-fx-text-fill: #f59e0b;");
                        addBotMessage("SoterIA Setup: Provisional hearing (STT) models for " + systemCapability.getRecommendedProfile() + "...");
                    });
                    modelManager.downloadVoskModel(currentLanguage).join();
                }

                // 2. Download Brain ( Reasoning )
                String brainName = modelManager.getBrainModelFileName();
                if (!modelManager.isBrainModelReady()) {
                    Platform.runLater(() -> {
                        aiStatusLabel.setText("Soteria: ⏳ Downloading Brain...");
                        addBotMessage("SoterIA Setup: Provisioning brain (" + brainName + ") for specialized offline reasoning...");
                    });
                    modelManager.downloadBrainModel().join();
                }

                // Initializing Services
                sttService = new VoskSTTService(modelManager.getVoskModelPath(currentLanguage));
                brainService = new LocalBrainService(modelManager.getBrainModelPath());

                Platform.runLater(() -> {
                    aiAvailable = true;
                    aiStatusLabel.setText("Soteria: ✅ Native AI Ready (" + systemCapability.getRecommendedProfile() + ")");
                    aiStatusLabel.setStyle("-fx-text-fill: #10b981;");
                    setStatus(STATUS_READY);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    aiStatusLabel.setText("Soteria: ❌ Error");
                    aiStatusLabel.setStyle("-fx-text-fill: #ef4444;");
                    setStatus("Initialization Failed");
                });
            }
        }).start();
    }

    public void setUserData(UserData userData) {
        this.currentUser = userData;
        addBotMessage("Hi " + userData.fullName() + "! 👋\n\n" +
                     "I am **Soteria**, your emergency assistant. I can help you by:\n" +
                     "• Classifying emergencies via text or voice\n" +
                     "• Providing action instructions\n" +
                     "• Sending alerts to emergency services\n\n" +
                     "Describe what is happening or press 🎤 to speak.");
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
        toggleRecording();
    }

    private void toggleRecording() {
        if (!aiAvailable) {
            addBotMessage("⚠️ System is still initializing models. Please wait.");
            return;
        }

        if (!isRecording) {
            isRecording = true;
            voiceButton.setText("⏹️");
            voiceButton.setStyle("-fx-background-color: #ef4444;");
            setStatus("🎤 Listening...");

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
                    Platform.runLater(() -> setStatus("🎤 " + text));
                }
                @Override public void onError(Throwable t) {
                    Platform.runLater(() -> setStatus("Mic Error: " + t.getMessage()));
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
        voiceButton.setStyle("");
        setStatus(STATUS_READY);
    }

    private void processMessage(String message) {
        if (isEmergencyCommand(message)) {
            handleEmergencyAlert(message);
            return;
        }

        if (knowledgeBase == null) return;

        conversationHistory.add(ChatMessage.user(message));
        trimHistory();

        setStatus("AI Thinking...");
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
        }).start();
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
        HBox messageBox = new HBox();
        messageBox.setAlignment(Pos.CENTER_RIGHT);
        messageBox.setPadding(new Insets(5, 0, 5, 50));
        
        VBox bubble = new VBox();
        bubble.setStyle("-fx-background-color: #2563eb; -fx-background-radius: 15; -fx-padding: 10 15;");
        
        Text text = new Text(message);
        text.setStyle("-fx-fill: white; -fx-font-size: 14;");
        text.setWrappingWidth(300);
        
        bubble.getChildren().add(text);
        messageBox.getChildren().add(bubble);
        chatMessages.getChildren().add(messageBox);
        scrollToBottom();
    }

    private void addBotMessage(String message) {
        HBox messageBox = new HBox();
        messageBox.setAlignment(Pos.CENTER_LEFT);
        messageBox.setPadding(new Insets(5, 50, 5, 0));
        
        VBox bubble = new VBox();
        bubble.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 15; -fx-padding: 10 15;");
        
        TextFlow textFlow = new TextFlow();
        Text text = new Text(message);
        text.setStyle("-fx-fill: #1e293b; -fx-font-size: 14;");
        textFlow.getChildren().add(text);
        
        bubble.getChildren().add(textFlow);
        messageBox.getChildren().add(bubble);
        chatMessages.getChildren().add(messageBox);
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
        return msg.contains("112") || msg.contains("alert") || msg.contains("emergency") || 
               msg.contains("help") || msg.contains("ambulance");
    }

    private void handleEmergencyAlert(String message) {
        setStatus("🚨 SENDING ALERT...");
        
        new Thread(() -> {
            try {
                String location = locationProvider.getLocationDescription();
                
                EmergencyEvent event = new EmergencyEvent(
                    "CHAT EMERGENCY: " + message,
                    location,
                    10,
                    currentUser != null ? currentUser.fullName() : "Unknown"
                );

                boolean success = alertService.send(event);

                Platform.runLater(() -> {
                    if (success) {
                        addBotMessage("🚨 **EMERGENCY ALERT SENT** 🚨\n\n" +
                                     "Detected location: " + location + "\n" +
                                     "Professional help is on the way. Stay calm.");
                        setStatus("🚨 ACTIVE ALERT");
                    } else {
                        addBotMessage("⚠️ Automated alert failed. Please call 112/911 immediately.");
                        setStatus("Error");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("Error"));
            }
        }).start();
    }
}
