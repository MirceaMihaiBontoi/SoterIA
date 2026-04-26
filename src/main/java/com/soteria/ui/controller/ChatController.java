package com.soteria.ui.controller;

import com.soteria.core.interfaces.AlertService;
import com.soteria.core.interfaces.LocationProvider;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.intelligence.*;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.component.SoterIAFace;
import com.soteria.core.model.UserData;
import com.soteria.ui.logic.ChatViewManager;
import com.soteria.ui.logic.InferenceEngine;
import com.soteria.ui.logic.SessionCoordinator;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;



public class ChatController implements InferenceEngine.UIUpdateListener {
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
    private SoterIAFace face;
    private UserData currentUser;
    private ChatSession activeSession;

    // Services
    private VoskSTTService sttService;
    private EmergencyKnowledgeBase knowledgeBase;
    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();

    // Managers (Refactored Logic)
    private ChatViewManager viewManager;
    private InferenceEngine inferenceEngine;
    private SessionCoordinator sessionCoordinator;

    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private String currentLanguage = "Spanish";

    @FXML
    private void initialize() {
        face = new SoterIAFace(85);
        faceHolder.getChildren().add(face);
        
        // Initialize managers
        ChatViewManager.UIComponents ui = new ChatViewManager.UIComponents(
            chatMessages, chatScrollPane, chatSheet, subtitleLabel, 
            partialTranscriptLabel, aiStatusLabel, statusDot
        );
        viewManager = new ChatViewManager(ui);
        sessionCoordinator = new SessionCoordinator(sessionList, historySidebar);
        
        viewManager.setSubtitle(PROMPT_READY);
        viewManager.setAiStatusPill("Preparando IA", STATUS_WARMING);
        setInputLocked(true);
        handleNewChat();
    }

    public void init(UserData profile, BootstrapService bootstrap) {
        this.currentUser = profile;
        viewManager.addBotMessage("Hola " + profile.fullName() + ". Soy SoterIA. "
                + "Pulsa el micro y cuéntame qué pasa, o escribe si no puedes hablar.");

        viewManager.setAiStatusPill("Cargando", STATUS_WARMING);
        
        // Restore initial status check
        String initialStatus = bootstrap.statusProperty().get();
        if (initialStatus != null && !initialStatus.isBlank()) {
            viewManager.setSubtitle(initialStatus);
        }

        bootstrap.statusProperty().addListener((obs, old, nw) -> {
            if (!aiAvailable && nw != null && !nw.isBlank()) {
                viewManager.setSubtitle(nw);
            }
        });

        bootstrap.ready().whenComplete((ok, err) -> Platform.runLater(() -> {
            if (err != null) {
                viewManager.setAiStatusPill("IA offline", STATUS_OFFLINE);
                viewManager.setSubtitle("No puedo escucharte ahora. Usa el botón SOS si es urgente.");
                face.setState(SoterIAFace.State.IDLE);
                return;
            }
            this.sttService = bootstrap.sttService();
            this.knowledgeBase = bootstrap.knowledgeBase();
            this.inferenceEngine = new InferenceEngine(bootstrap.triageService(), bootstrap.brainService(), knowledgeBase);
            this.aiAvailable = true;
            viewManager.setAiStatusPill("IA lista", STATUS_READY);
            viewManager.setSubtitle(PROMPT_READY);
            setInputLocked(false);
        }));
    }

    @FXML
    private void handleSendMessage() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;
        viewManager.addUserMessage(message);
        messageInput.clear();
        processMessage(message);
    }

    @FXML
    private void handleVoiceInput() {
        if (!aiAvailable) {
            viewManager.setSubtitle("La IA aún se está preparando. Espera unos segundos.");
            return;
        }

        if (!isRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        isRecording = true;
        micButton.getStyleClass().add("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-stop");
        face.setState(SoterIAFace.State.LISTENING);
        viewManager.setSubtitle("Escuchando…");
        sttService.startListening(new STTListener() {
            @Override
            public void onResult(String text) {
                if (!text.isEmpty()) {
                    Platform.runLater(() -> {
                        stopRecording();
                        viewManager.addUserMessage(text);
                        processMessage(text);
                    });
                }
            }
            @Override
            public void onPartialResult(String text) {
                viewManager.setPartialTranscript(text);
            }
            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    stopRecording();
                    viewManager.setSubtitle("Error del micro: " + t.getMessage());
                });
            }
        });
    }

    private void stopRecording() {
        isRecording = false;
        sttService.stopListening();
        micButton.getStyleClass().remove("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-mic");
        viewManager.setPartialTranscript("");
        face.setState(SoterIAFace.State.IDLE);
        viewManager.setSubtitle(PROMPT_READY);
    }

    private void processMessage(String message) {
        if (inferenceEngine != null && inferenceEngine.isEmergencyCommand(message)) {
            handleEmergencyAlert("Palabra clave de emergencia: " + message);
            return;
        }
        if (!aiAvailable) return;

        activeSession.addMessage(ChatMessage.user(message));
        face.setState(SoterIAFace.State.THINKING);
        viewManager.setSubtitle("Pensando…");

        new Thread(() -> inferenceEngine.runInference(message, activeSession, currentUser, currentLanguage, this), 
                "soteria-inference").start();
    }

    // --- Inference Engine Callbacks ---

    @Override
    public void onSubtitleUpdate(String text) {
        viewManager.setSubtitle(text);
    }

    @Override
    public void onFaceStateChange(String state) {
        Platform.runLater(() -> face.setState(SoterIAFace.State.valueOf(state)));
    }

    @Override
    public void onResponseFinalized(String finalMessage, String query) {
        Platform.runLater(() -> {
            activeSession.addMessage(ChatMessage.model(finalMessage));
            activeSession.setTimestamp(System.currentTimeMillis());
            
            // Dynamic Titling
            if (activeSession.getMessages().size() <= 2) {
                String title = query.substring(0, Math.min(query.length(), 25));
                if (query.length() > 25) title += "...";
                activeSession.setTitle(title);
            }

            sessionCoordinator.saveCurrentSession();
            viewManager.addBotMessage(finalMessage);
            face.setState(SoterIAFace.State.IDLE);
            viewManager.setSubtitle(PROMPT_READY);
        });
    }

    @Override
    public void onSafetyBoxUpdate(String protocolId, String status) {
        Platform.runLater(() -> {
            if ("N/A".equals(protocolId) || "RESOLVED".equals(status) || "INACTIVE".equals(status)) {
                if ("RESOLVED".equals(status)) {
                    activeSession.setActiveEmergencyId(null);
                    activeSession.setProtocolLocked(false);
                    activeSession.getProtocolProgress().clear();
                    viewManager.setAiStatusPill("Sistema Listo", STATUS_READY);
                }
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

            for (String step : protocol.getContent().split("\n")) {
                if (step.trim().isEmpty()) continue;
                Label stepLabel = new Label("• " + step.trim());
                stepLabel.getStyleClass().add("safety-step");
                stepLabel.setWrapText(true);
                safetyContainer.getChildren().add(stepLabel);
            }

            if ("ACTIVE".equals(status)) {
                activeSession.setActiveEmergencyId(protocolId);
                viewManager.setAiStatusPill("Emergencia Activa", STATUS_ALERT);
                face.setState(SoterIAFace.State.ALERT);
            }
        });
    }

    // --- Emergency Handling ---

    @FXML
    private void handleEmergencyButton() {
        handleEmergencyAlert("Botón SOS pulsado manualmente");
    }

    private void handleEmergencyAlert(String reason) {
        face.setState(SoterIAFace.State.ALERT);
        viewManager.setAiStatusPill("Alerta activa", STATUS_ALERT);
        viewManager.setSubtitle("Enviando alerta…");
        new Thread(() -> {
            try {
                String location = locationProvider.getLocationDescription();
                com.soteria.core.model.EmergencyEvent event = new com.soteria.core.model.EmergencyEvent(
                        "EMERGENCY: " + reason,
                        location,
                        10,
                        currentUser != null ? currentUser.fullName() : "Usuario desconocido");
                
                boolean success = alertService.send(event);
                Platform.runLater(() -> {
                    if (success) {
                        viewManager.addBotMessage("Alerta enviada. Ubicación detectada: " + location
                                + ". La ayuda está en camino — mantén la calma.");
                        viewManager.setSubtitle("Alerta enviada. Mantente en la línea.");
                    } else {
                        viewManager.addBotMessage("No pude enviar la alerta automática. Llama al 112/911 ahora.");
                        viewManager.setSubtitle("Llama al 112 directamente.");
                        viewManager.setAiStatusPill("Alerta falló", STATUS_OFFLINE);
                    }
                });
            } catch (Exception _) {
                Platform.runLater(() -> {
                    viewManager.setSubtitle("Error al enviar alerta.");
                    viewManager.setAiStatusPill("Alerta falló", STATUS_OFFLINE);
                });
            }
        }, "soteria-alert").start();
    }

    public boolean isEmergencyCommand(String message) {
        return inferenceEngine != null && inferenceEngine.isEmergencyCommand(message);
    }

    // --- UI Delegation ---

    @FXML private void toggleChatSheet() { viewManager.toggleChatSheet(); }
    @FXML private void closeChatSheet() { viewManager.closeChatSheet(); }
    @FXML private void toggleHistorySidebar() { sessionCoordinator.toggleHistorySidebar(this::refreshSessionList); }

    @FXML
    private void handleNewChat() {
        this.activeSession = sessionCoordinator.startNewSession();
        viewManager.clearMessages();
        if (aiAvailable) viewManager.addBotMessage("Nueva sesión de emergencia iniciada. Dime qué sucede.");
    }

    private void refreshSessionList() {
        sessionCoordinator.refreshSessionList(activeSession, this::loadSession);
    }

    private void loadSession(ChatSession session) {
        this.activeSession = session;
        sessionCoordinator.setActiveSession(session);
        viewManager.clearMessages();
        for (ChatMessage msg : session.getMessages()) {
            if ("user".equals(msg.role())) viewManager.addUserMessage(msg.content());
            else viewManager.addBotMessage(msg.content());
        }
    }

    private void setInputLocked(boolean locked) {
        micButton.setDisable(locked);
        chatButton.setDisable(locked);
        if (sendButton != null) sendButton.setDisable(locked);
        if (messageInput != null) messageInput.setDisable(locked);
    }

}
