package com.soteria.ui.controller;

import com.soteria.core.port.AlertService;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.LocationProvider;
import com.soteria.core.port.STT;
import com.soteria.core.port.STTListener;
import com.soteria.core.port.TTS;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.view.SoterIAFace;
import com.soteria.core.model.UserData;
import com.soteria.ui.view.ChatViewManager;
import com.soteria.application.chat.InferenceEngine;
import com.soteria.ui.view.SessionCoordinator;

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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;



public class ChatController implements InferenceEngine.UIUpdateListener {
    private static final Logger logger = Logger.getLogger(ChatController.class.getName());
    private final String instanceId = "ChatController-" + java.util.UUID.randomUUID().toString().substring(0, 8);
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
    @FXML private Button ttsToggle;
    @FXML private FontIcon ttsIcon;
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
    private STT sttService;
    private TTS ttsService;
    private KnowledgeBase knowledgeBase;
    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();

    // Managers (Refactored Logic)
    private ChatViewManager viewManager;
    private InferenceEngine inferenceEngine;
    private SessionCoordinator sessionCoordinator;
    private com.soteria.infrastructure.intelligence.kws.WakeWordService wakeWordService;

    private boolean aiAvailable = false;
    private boolean botMessageStarted = false;
    private boolean isRecording = false;
    /** After the first meaningful partial for this mic turn, assistant output has already been cut. */
    private boolean haltedAssistantOnPartial = false;
    private boolean ttsEnabled = true;
    private String currentLanguage = "English";

    /** Bumped whenever a turn is invalidated (barge-in / cancel); inference UI respects only matching correlation IDs. */
    private final AtomicLong inferenceGeneration = new AtomicLong(0);

    /** Serialized wait for overlapping TTS completions so IDLE is not raced by multiple waiter threads. */
    private CompletableFuture<Void> ttsIdleChainTail = CompletableFuture.completedFuture(null);
    private final Object ttsIdleChainLock = new Object();

    private String lastDedupedOutboundText = "";
    private long lastDedupedOutboundAtMs;

    private static final long DUPLICATE_CHAT_SUBMIT_GUARD_MS = 450;

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
        if (profile.preferredLanguage() != null) {
            this.currentLanguage = profile.preferredLanguage();
        }
        
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
                logger.log(Level.SEVERE, "[{0}] Bootstrap failed: {1}", new Object[]{instanceId, err.getMessage()});
                viewManager.setAiStatusPill("IA offline", STATUS_OFFLINE);
                viewManager.setSubtitle("No puedo escucharte ahora. Usa el botón SOS si es urgente.");
                face.setState(SoterIAFace.State.IDLE);
                return;
            }
            logger.log(Level.INFO, "[{0}] ready() complete. Configuring services...", instanceId);
            this.sttService = bootstrap.sttService();
            this.ttsService = bootstrap.ttsService();
            this.knowledgeBase = bootstrap.knowledgeBase();
            this.inferenceEngine = new InferenceEngine(bootstrap.triageService(), bootstrap.brainService(), knowledgeBase);
            this.wakeWordService = bootstrap.wakeWordService();
            this.aiAvailable = true;

            if (this.wakeWordService != null) {
                logger.log(Level.INFO, "[{0}] Registering wake-word listener.", instanceId);
                this.wakeWordService.startListening(this::onWakeWordDetected);
            }

            viewManager.setAiStatusPill("IA lista", STATUS_READY);
            viewManager.setSubtitle(PROMPT_READY);
            setInputLocked(false);
        }));
    }

    /** Stops TTS and signals the brain to abandon the current generation (voice or text bump-in). */
    private void interruptOngoingGeneration() {
        inferenceGeneration.incrementAndGet();
        if (ttsService != null) {
            ttsService.stop();
        }
        if (inferenceEngine != null) {
            inferenceEngine.cancel();
        }
        botMessageStarted = false;
    }

    private void prepareForInput() {
        interruptOngoingGeneration();
        viewManager.setPartialTranscript("");
        face.setState(SoterIAFace.State.LISTENING);
    }

    public void onWakeWordDetected() {
        logger.log(Level.INFO, "[{0}] Wake-word callback received by active controller", instanceId);
        Platform.runLater(() -> {
            prepareForInput();
            beginVoiceCapture();
        });
    }

    @FXML
    private void handleSendMessage() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String dedupKey = normalizeForDedupeGuard(message);
        if (dedupKey.equals(lastDedupedOutboundText) && now - lastDedupedOutboundAtMs < DUPLICATE_CHAT_SUBMIT_GUARD_MS) {
            logger.log(Level.FINE, "[{0}] Send ignored — duplicate rapid submit.", instanceId);
            return;
        }
        lastDedupedOutboundText = dedupKey;
        lastDedupedOutboundAtMs = now;

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
            prepareForInput();
            beginVoiceCapture();
        } else {
            stopRecording();
        }
    }

    private void beginVoiceCapture() {
        if (sttService == null) {
            logger.log(Level.WARNING, "[{0}] beginVoiceCapture: STT null.", instanceId);
            viewManager.setSubtitle(
                    "Reconocimiento de voz no disponible. Intenta escribir el mensaje.");
            return;
        }
        isRecording = true;
        haltedAssistantOnPartial = false;
        if (wakeWordService != null) {
            wakeWordService.stopListening(); // Pause wake-word to free mic
        }
        micButton.getStyleClass().add("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-stop");
        face.setState(SoterIAFace.State.LISTENING);
        viewManager.setSubtitle("Escuchando…");
        viewManager.setPartialTranscript("");
        sttService.startListening(new STTListener() {
            @Override
            public void onResult(String text) {
                if (!text.isEmpty()) {
                    logger.log(Level.INFO, "[{0}] STT Result: ''{1}''", new Object[]{instanceId, text});
                    // Filter out accidental transcription of the wake-word itself
                    String cleanText = text.toLowerCase().replaceAll("[^a-z]", "");
                    if (cleanText.equals("soteria")) {
                        logger.log(Level.INFO, "[{0}] STT: Ignored wake-word ''SoterIA'' in result stream. Cleaning up.", instanceId);
                        Platform.runLater(() -> stopRecording());
                        return;
                    }

                    Platform.runLater(() -> {
                        long now = System.currentTimeMillis();
                        String dedupKey = normalizeForDedupeGuard(text);
                        if (dedupKey.equals(lastDedupedOutboundText)
                                && now - lastDedupedOutboundAtMs < DUPLICATE_CHAT_SUBMIT_GUARD_MS) {
                            stopRecording();
                            logger.log(Level.FINE, "[{0}] Ignored STT duplicate submit.", instanceId);
                            return;
                        }
                        lastDedupedOutboundText = dedupKey;
                        lastDedupedOutboundAtMs = now;

                        logger.log(Level.INFO, "[{0}] Processing message: ''{1}''", new Object[]{instanceId, text});
                        stopRecording();
                        viewManager.addUserMessage(text);
                        processMessage(text);
                    });
                } else {
                    logger.log(Level.INFO, "[{0}] STT: Empty result received.", instanceId);
                    Platform.runLater(() -> stopRecording());
                }
            }
            @Override
            public void onPartialResult(String text) {
                Platform.runLater(() -> {
                    if (text != null && !text.isBlank() && !haltedAssistantOnPartial) {
                        haltedAssistantOnPartial = true;
                        interruptOngoingGeneration();
                        face.setState(SoterIAFace.State.LISTENING);
                        viewManager.setSubtitle("Escuchando…");
                        logger.log(Level.INFO,
                                "[{0}] STT partial: interrupted assistant TTS/streaming inference.",
                                instanceId);
                    }
                    viewManager.setPartialTranscript(text != null ? text : "");
                });
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
        if (sttService != null) {
            sttService.stopListening();
        }
        micButton.getStyleClass().remove("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-mic");
        
        if (wakeWordService != null) {
            wakeWordService.startListening(this::onWakeWordDetected);
        }
        // Do NOT clear partial transcript here, let processMessage handle it
        face.setState(SoterIAFace.State.IDLE);
    }

    private void processMessage(String message) {
        if (!aiAvailable) {
            logger.log(Level.WARNING, "[{0}] processMessage called but AI not available.", instanceId);
            return;
        }

        logger.log(Level.INFO, "[{0}] processMessage: ''{1}''", new Object[]{instanceId, message});
        interruptOngoingGeneration();
        final long correlationId = inferenceGeneration.get();
        activeSession.addMessage(ChatMessage.user(message));
        face.setState(SoterIAFace.State.THINKING);
        viewManager.setSubtitle("Pensando…");
        viewManager.setPartialTranscript(message); // Keep the transcribed text visible under the face
        viewManager.showThinkingIndicator();

        new Thread(() -> {
            logger.log(Level.INFO, "[{0}] Starting inference thread.", instanceId);
            inferenceEngine.runInference(message, activeSession, currentUser, currentLanguage,
                    this, inferenceGeneration, correlationId);
        }, "soteria-inference").start();
    }

    // --- Inference Engine Callbacks ---

    @Override
    public void onSubtitleUpdate(String text) {
        Platform.runLater(() -> {
            if (!botMessageStarted) {
                viewManager.startBotMessage();
                botMessageStarted = true;
            }
            viewManager.setSubtitle(text);
            viewManager.updateBotMessage(text);
        });
    }

    @Override
    public void onFaceStateChange(String state) {
        Platform.runLater(() -> face.setState(SoterIAFace.State.valueOf(state)));
    }

    @Override
    public void onResponseFinalized(String finalMessage, String query) {
        Platform.runLater(() -> {
            updateActiveSession(finalMessage, query);
            sessionCoordinator.saveCurrentSession();
            viewManager.updateBotMessage(finalMessage);

            if (ttsEnabled && ttsService != null && ttsService.isSpeaking()) {
                waitForSpeechSilenceThen(() -> face.setState(SoterIAFace.State.IDLE));
            } else {
                face.setState(SoterIAFace.State.IDLE);
            }

            viewManager.setSubtitle(PROMPT_READY);
        });
    }

    /** Chains sequential background waits while {@link #ttsIdleChainTail} drains speaking state. */
    private void waitForSpeechSilenceThen(Runnable javafxWork) {
        synchronized (ttsIdleChainLock) {
            ttsIdleChainTail = ttsIdleChainTail
                    .handle((ok, err) -> null)
                    .thenRunAsync(() -> {
                        TTS tts = ttsService;
                        if (tts == null) {
                            return;
                        }
                        try {
                            while (tts.isSpeaking()) {
                                Thread.sleep(80);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .thenRun(() -> Platform.runLater(javafxWork))
                    .whenComplete((r, err) -> {
                        if (err != null) {
                            logger.log(Level.FINE, "TTS idle chain step failed (ignored)", err.getCause());
                        }
                    });
        }
    }

    /** Collapses whitespace for trivial duplicate submits (rapid double Enter / echoes). */
    private static String normalizeForDedupeGuard(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private void updateActiveSession(String finalMessage, String query) {
        activeSession.addMessage(ChatMessage.model(finalMessage));
        activeSession.setTimestamp(System.currentTimeMillis());

        if (activeSession.getMessages().size() <= 2) {
            String title = query.substring(0, Math.min(query.length(), 25));
            if (query.length() > 25) title += "...";
            activeSession.setTitle(title);
        }
    }

    @Override
    public void onSpeakSentence(String sentence, String language) {
        if (ttsEnabled && ttsService != null) {
            ttsService.setLanguage(language);
            ttsService.speakQueued(sentence);
        }
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

    @FXML
    private void toggleTTS() {
        ttsEnabled = !ttsEnabled;
        if (ttsIcon != null) {
            ttsIcon.setIconLiteral(ttsEnabled ? "mdmz-volume_up" : "mdmz-volume_off");
        }
        if (ttsService != null && !ttsEnabled) {
            ttsService.stop();
        }
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
