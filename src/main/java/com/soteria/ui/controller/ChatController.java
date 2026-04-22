package com.soteria.ui.controller;

import com.soteria.core.interfaces.AlertService;
import com.soteria.core.interfaces.LocationProvider;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.intelligence.ChatMessage;
import com.soteria.infrastructure.intelligence.LocalBrainService;
import com.soteria.infrastructure.intelligence.EmergencyKnowledgeBase;
import com.soteria.infrastructure.intelligence.Protocol;
import com.soteria.infrastructure.intelligence.STTListener;
import com.soteria.infrastructure.intelligence.VoskSTTService;
import com.soteria.infrastructure.intelligence.ChatSession;
import com.soteria.infrastructure.persistence.ChatSessionRepository;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.component.SoterIAFace;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatController {
    private static final Logger logger = Logger.getLogger(ChatController.class.getName());

    private static final String PROMPT_READY = "Pulsa el micro para hablar";

    private static final String STATUS_READY = "ready";
    private static final String STATUS_WARMING = "warming";
    private static final String STATUS_OFFLINE = "offline";
    private static final String STATUS_ALERT = "alert";

    @FXML private StackPane faceHolder;
    @FXML private Label subtitleLabel;
    @FXML private Label partialTranscriptLabel;
    @FXML private Label aiStatusLabel;
    @FXML private Circle statusDot;
    @FXML private Button micButton;
    @FXML private FontIcon micIcon;
    @FXML private Button alertButton;
    @FXML private Button chatButton;
    @FXML private VBox chatSheet;
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button sendButton;
    @FXML private VBox historySidebar;
    @FXML private VBox sessionList;
    @FXML private VBox safetyContainer;
    @FXML private Button historyButton;

    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();
    private final ChatSessionRepository sessionRepository = new ChatSessionRepository();

    private SoterIAFace face;
    private UserData currentUser;
    private ChatSession activeSession;

    private VoskSTTService sttService;
    private LocalBrainService brainService;
    private EmergencyKnowledgeBase knowledgeBase;

    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private boolean sheetOpen = false;
    private String currentLanguage = "Spanish";

    @FXML
    private void initialize() {
        face = new SoterIAFace(85);
        faceHolder.getChildren().add(face);
        setSubtitle(PROMPT_READY);
        setPartialTranscript("");
        setAiStatusPill("Preparando IA", STATUS_WARMING);
        setInputLocked(true);
        startNewChat();
    }

    private void setInputLocked(boolean locked) {
        micButton.setDisable(locked);
        chatButton.setDisable(locked);
        if (sendButton != null) sendButton.setDisable(locked);
        if (messageInput != null) messageInput.setDisable(locked);
    }

    public void init(UserData profile, BootstrapService bootstrap) {
        this.currentUser = profile;
        addBotMessage("Hola " + profile.fullName() + ". Soy SoterIA. "
                + "Pulsa el micro y cuéntame qué pasa, o escribe si no puedes hablar.");

        setAiStatusPill("Cargando", STATUS_WARMING);
        String initialStatus = bootstrap.statusProperty().get();
        setSubtitle(initialStatus == null || initialStatus.isBlank() ? "Preparando asistente…" : initialStatus);

        // Mirror boot progress in the subtitle until the AI is up. Once ready
        // we detach and the subtitle goes back to user-facing prompts.
        bootstrap.statusProperty().addListener((obs, old, nw) -> {
            if (!aiAvailable && nw != null && !nw.isBlank()) {
                setSubtitle(nw);
            }
        });

        bootstrap.ready().whenComplete((ok, err) -> Platform.runLater(() -> {
            if (err != null) {
                setAiStatusPill("IA offline", STATUS_OFFLINE);
                setSubtitle("No puedo escucharte ahora. Usa el botón SOS si es urgente.");
                face.setState(SoterIAFace.State.IDLE);
                return;
            }
            this.sttService = bootstrap.sttService();
            this.brainService = bootstrap.brainService();
            this.knowledgeBase = bootstrap.knowledgeBase();
            this.aiAvailable = true;
            setAiStatusPill("IA lista", STATUS_READY);
            setSubtitle(PROMPT_READY);
            setInputLocked(false);
        }));
    }

    @FXML
    private void handleSendMessage() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;
        addUserMessage(message);
        messageInput.clear();
        processMessage(message);
    }

    @FXML
    private void handleVoiceInput() {
        if (!aiAvailable) {
            setSubtitle("La IA aún se está preparando. Espera unos segundos.");
            return;
        }

        if (!isRecording) {
            isRecording = true;
            micButton.getStyleClass().add("mic-fab-active");
            if (micIcon != null) micIcon.setIconLiteral("mdmz-stop");
            face.setState(SoterIAFace.State.LISTENING);
            setSubtitle("Escuchando…");
            setPartialTranscript("");
            sttService.startListening(new STTListener() {
                @Override public void onResult(String text) {
                    if (!text.isEmpty()) {
                        Platform.runLater(() -> {
                            stopRecordingUI();
                            sttService.stopListening();
                            addUserMessage(text);
                            processMessage(text);
                        });
                    }
                }
                @Override public void onPartialResult(String text) {
                    Platform.runLater(() -> setPartialTranscript(text));
                }
                @Override public void onError(Throwable t) {
                    Platform.runLater(() -> {
                        stopRecordingUI();
                        setSubtitle("Error del micro: " + t.getMessage());
                    });
                }
            });
        } else {
            stopRecordingUI();
            sttService.stopListening();
        }
    }

    @FXML
    private void handleEmergencyButton() {
        handleEmergencyAlert("Botón SOS pulsado manualmente");
    }

    @FXML
    private void toggleChatSheet() {
        if (sheetOpen) closeChatSheet();
        else openChatSheet();
    }

    @FXML
    private void closeChatSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(220),
                        new KeyValue(chatSheet.translateYProperty(),
                                chatSheet.getHeight() > 0 ? chatSheet.getHeight() : 520,
                                Interpolator.EASE_IN),
                        new KeyValue(chatSheet.opacityProperty(), 0, Interpolator.EASE_IN))
        );
        tl.setOnFinished(e -> {
            chatSheet.setVisible(false);
            chatSheet.setManaged(false);
        });
        tl.play();
    }

    private void openChatSheet() {
        sheetOpen = true;
        chatSheet.setVisible(true);
        chatSheet.setManaged(true);
        double start = chatSheet.getHeight() > 0 ? chatSheet.getHeight() : 520;
        chatSheet.setTranslateY(start);
        chatSheet.setOpacity(0);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(240),
                        new KeyValue(chatSheet.translateYProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(chatSheet.opacityProperty(), 1, Interpolator.EASE_OUT))
        );
        tl.play();
    }

    private void stopRecordingUI() {
        isRecording = false;
        micButton.getStyleClass().remove("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-mic");
        setPartialTranscript("");
        if (face.getState() == SoterIAFace.State.LISTENING) {
            face.setState(SoterIAFace.State.IDLE);
        }
        setSubtitle(PROMPT_READY);
    }

    private void processMessage(String message) {
        if (isEmergencyCommand(message)) {
            handleEmergencyAlert(message);
            return;
        }
        if (!aiAvailable) return;

        // Add to persistent session
        activeSession.addMessage(ChatMessage.user(message));
        
        face.setState(SoterIAFace.State.THINKING);
        setSubtitle("Pensando…");
        
        // Final snapshot for inference
        List<ChatMessage> snapshot = List.copyOf(activeSession.getMessages());
        new Thread(() -> runInferenceFlow(message, snapshot), "soteria-inference").start();
    }

    private static final float RAG_THRESHOLD = 0.25f;

    private void runInferenceFlow(String userMessage, List<ChatMessage> snapshot) {
        List<EmergencyKnowledgeBase.ProtocolMatch> results = performRagSearch(userMessage);
        
        // Filter out low-confidence results to prevent AI confusion
        List<EmergencyKnowledgeBase.ProtocolMatch> highConfidenceResults = results.stream()
            .filter(r -> r.score() > RAG_THRESHOLD)
            .toList();

        applyStickyContext(highConfidenceResults);
        String context = buildProtocolManifest(highConfidenceResults);
        final StringBuilder currentText = new StringBuilder();
        
        brainService.generateResponse(snapshot, context, currentLanguage, currentUser, new com.soteria.infrastructure.intelligence.InferenceListener() {
            @Override
            public void onToken(String token) {
                currentText.append(token);
                Platform.runLater(() -> setSubtitle(currentText.toString().trim()));
            }

            @Override
            public void onAnalysisComplete(String protocolId, String status) {
                Platform.runLater(() -> updateSafetyBox(protocolId, status));
            }

            @Override
            public void onComplete(String fullText) {
                logger.log(Level.FINE, "Inference flow completed. Captured {0} characters.", currentText.length());
                Platform.runLater(() -> finalizeTurn(fullText, userMessage));
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    setSubtitle("Error de conexión con la IA.");
                    face.setState(SoterIAFace.State.IDLE);
                });
            }
        });
    }

    private void updateSafetyBox(String protocolId, String status) {
        if ("N/A".equals(protocolId) || "RESOLVED".equals(status)) {
            safetyContainer.setVisible(false);
            safetyContainer.setManaged(false);
            return;
        }

        Protocol protocol = knowledgeBase.getProtocolById(protocolId);
        if (protocol == null) return;

        safetyContainer.getChildren().clear();
        safetyContainer.setVisible(true);
        safetyContainer.setManaged(true);

        Label title = new Label(protocol.getTitle().toUpperCase());
        title.getStyleClass().add("safety-title");
        safetyContainer.getChildren().add(title);

        // Deterministic Steps from Knowledge Base
        String[] steps = protocol.getContent().split("\n");
        for (String step : steps) {
            if (step.trim().isEmpty()) continue;
            Label stepLabel = new Label("• " + step.trim());
            stepLabel.getStyleClass().add("safety-step");
            stepLabel.setWrapText(true);
            safetyContainer.getChildren().add(stepLabel);
        }

        if ("ACTIVE".equals(status)) {
            activeSession.setActiveEmergencyId(protocolId);
            setAiStatusPill("Emergencia Activa", STATUS_ALERT);
            face.setState(SoterIAFace.State.ALERT);
        }
    }

    private List<EmergencyKnowledgeBase.ProtocolMatch> performRagSearch(String userMessage) {
        List<EmergencyKnowledgeBase.ProtocolMatch> rawResults = knowledgeBase.findProtocols(
            userMessage, 
            activeSession.getRejectedProtocolIds()
        );
        
        brainService.logChatMessage("--- RAG SEARCH RESULTS ---");
        if (rawResults.isEmpty()) {
            brainService.logChatMessage("No matches found.");
        } else {
            for (EmergencyKnowledgeBase.ProtocolMatch m : rawResults) {
                brainService.logChatMessage(String.format("Found: [%s] (Score: %.4f)", m.protocol().getId(), m.score()));
            }
        }
        brainService.logChatMessage("--------------------------");
        
        List<EmergencyKnowledgeBase.ProtocolMatch> filtered = new ArrayList<>();
        for (EmergencyKnowledgeBase.ProtocolMatch match : rawResults) {
            if (match.score() >= RAG_THRESHOLD) {
                filtered.add(match);
            }
        }
        return filtered;
    }

    private void applyStickyContext(List<EmergencyKnowledgeBase.ProtocolMatch> results) {
        String activeId = activeSession.getActiveEmergencyId();
        if (activeId == null) return;

        boolean alreadyPresent = results.stream().anyMatch(r -> r.protocol().getId().equals(activeId));
        if (!alreadyPresent) {
            Protocol activeProto = knowledgeBase.getProtocolById(activeId);
            if (activeProto != null) {
                results.add(0, new EmergencyKnowledgeBase.ProtocolMatch(activeProto, "PERSISTENT", 1.0f));
            }
        }
    }


    private void finalizeTurn(String finalResponse, String originalMessage) {
        activeSession.addMessage(ChatMessage.model(finalResponse));
        activeSession.setTimestamp(System.currentTimeMillis());
        
        // Dynamic Titling: If it's the first turn, set title based on input
        if (activeSession.getMessages().size() <= 2) {
            String title = originalMessage.substring(0, Math.min(originalMessage.length(), 25));
            if (originalMessage.length() > 25) title += "...";
            activeSession.setTitle(title);
        }

        try {
            sessionRepository.save(activeSession);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to auto-save session", e);
        }
        
        addBotMessage(finalResponse);
        face.setState(SoterIAFace.State.IDLE);
        setSubtitle(PROMPT_READY);
    }




    String buildProtocolManifest(List<EmergencyKnowledgeBase.ProtocolMatch> results) {
        if (results.isEmpty()) return "No specific protocol matched.";

        StringBuilder out = new StringBuilder();
        for (EmergencyKnowledgeBase.ProtocolMatch m : results) {
            String firstStep = (m.protocol().getSteps() != null && !m.protocol().getSteps().isEmpty()) 
                ? m.protocol().getSteps().get(0) 
                : "No specific first instruction.";

            out.append("- ID: ").append(m.protocol().getId())
               .append(" | Title: ").append(m.protocol().getTitle())
               .append(" | FIRST_STEP_BASE: ").append(firstStep).append("\n");
        }
        return out.toString();
    }

    // --- SESSION MANAGEMENT ---

    @FXML
    private void toggleHistorySidebar() {
        boolean visible = !historySidebar.isVisible();
        historySidebar.setVisible(visible);
        historySidebar.setManaged(visible);
        if (visible) refreshSessionList();
    }

    @FXML
    private void handleNewChat() {
        startNewChat();
        chatMessages.getChildren().clear();
        addBotMessage("Nueva sesión de emergencia iniciada. Dime qué sucede.");
        toggleHistorySidebar();
    }

    private void startNewChat() {
        activeSession = new ChatSession();
        try {
            sessionRepository.save(activeSession);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not persist new session", e);
        }
    }

    private void refreshSessionList() {
        sessionList.getChildren().clear();
        List<ChatSession> sessions = sessionRepository.listSessions();
        for (ChatSession session : sessions) {
            sessionList.getChildren().add(createSessionItem(session));
        }
    }

    private VBox createSessionItem(ChatSession session) {
        VBox item = new VBox(2);
        item.getStyleClass().add("session-item");
        if (session.getId().equals(activeSession.getId())) {
            item.getStyleClass().add("session-item-selected");
        }

        Label title = new Label(session.getTitle());
        title.getStyleClass().add("session-title");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM HH:mm");
        java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(session.getTimestamp())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        Label date = new Label(dt.format(dtf));
        date.getStyleClass().add("session-date");

        item.getChildren().addAll(title, date);
        item.setOnMouseClicked(e -> loadSession(session));
        return item;
    }

    private void loadSession(ChatSession session) {
        activeSession = session;
        chatMessages.getChildren().clear();
        for (ChatMessage msg : session.getMessages()) {
            if ("user".equals(msg.role())) addUserMessage(msg.content());
            else addBotMessage(msg.content());
        }
        toggleHistorySidebar();
    }

    private void addUserMessage(String message) {
        addBubble(message, "chat-bubble-user", Pos.CENTER_RIGHT, new Insets(5, 0, 5, 50));
    }

    private void addBotMessage(String message) {
        addBubble(message, "chat-bubble-bot", Pos.CENTER_LEFT, new Insets(5, 50, 5, 0));
    }

    private Label addBubble(String message, String bubbleClass, Pos alignment, Insets padding) {
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

        chatMessages.getChildren().add(box);
        scrollToBottom();
        
        // Return a dummy label for compatibility if needed, or null
        return new Label(message);
    }

    private void renderMarkdown(String text, TextFlow flow, String styleClass) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // Simple detection for numbered or bulleted lists
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
        // Split by ** for bold sections
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
                // Apply bold and a slight brightness boost for readability
                segment.setStyle("-fx-font-weight: bold;");
            }
            flow.getChildren().add(segment);
            isBold = !isBold;
        }
    }

    private void scrollToBottom() {
        Platform.runLater(() -> {
            chatScrollPane.layout();
            chatScrollPane.setVvalue(1.0);
        });
    }

    private void setSubtitle(String text) {
        Platform.runLater(() -> subtitleLabel.setText(text));
    }

    private void setPartialTranscript(String text) {
        Platform.runLater(() -> partialTranscriptLabel.setText(text == null ? "" : text));
    }

    private void setAiStatusPill(String text, String dotClass) {
        Platform.runLater(() -> {
            aiStatusLabel.setText(text);
            statusDot.getStyleClass().removeAll(STATUS_READY, STATUS_WARMING, STATUS_OFFLINE, STATUS_ALERT);
            statusDot.getStyleClass().add(dotClass);
        });
    }

    boolean isEmergencyCommand(String message) {
        String msg = message.toLowerCase();
        return msg.contains("112") || msg.contains(STATUS_ALERT) || msg.contains("emergency")
                || msg.contains("help") || msg.contains("ambulance");
    }

    private void handleEmergencyAlert(String message) {
        face.setState(SoterIAFace.State.ALERT);
        setAiStatusPill("Alerta activa", STATUS_ALERT);
        setSubtitle("Enviando alerta…");
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
                        addBotMessage("Alerta enviada. Ubicación detectada: " + location
                                + ". La ayuda está en camino — mantén la calma.");
                        setSubtitle("Alerta enviada. Mantente en la línea.");
                    } else {
                        addBotMessage("No pude enviar la alerta automática. Llama al 112/911 ahora.");
                        setSubtitle("Llama al 112 directamente.");
                        setAiStatusPill("Alerta falló", STATUS_OFFLINE);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setSubtitle("Error al enviar alerta.");
                    setAiStatusPill("Alerta falló", STATUS_OFFLINE);
                });
            }
        }, "soteria-alert").start();
    }
}
