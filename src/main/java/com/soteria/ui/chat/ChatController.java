package com.soteria.ui.chat;

import com.soteria.core.port.AlertService;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.LocationProvider;
import com.soteria.core.port.STT;
import com.soteria.core.port.TTS;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.view.SoterIAFace;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.persistence.ProfileRepository;
import com.soteria.ui.view.ChatViewManager;
import com.soteria.application.chat.InferenceEngine;
import com.soteria.ui.view.SessionCoordinator;

import com.soteria.ui.onboarding.OnboardingLanguageCatalog;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main HUD controller: microphone, chat sheet, wake word, inference wiring, SOS dispatch.
 * <p>
 * Dedupe, STT listener assembly, safety protocol layout, and {@link InferenceEngine} UI callbacks live in
 * {@link ChatOutboundDedupe}, {@link ChatSTTListenerFactory}, {@link ChatSafetyProtocolBinder}, and
 * {@link ChatInferenceUiBridge} (plus {@link ChatInputGuards}, {@link ChatTTSIdleChain}, {@link ChatEmergencyDispatch}).
 * </p>
 */
public class ChatController {
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
    @FXML private VBox settingsOverlay;
    @FXML private ComboBox<String> settingsThemeCombo;
    @FXML private ComboBox<String> settingsLanguageCombo;
    @FXML private Slider settingsSpeechRateSlider;
    @FXML private Label settingsSpeechRateLabel;
    @FXML private Label settingsModelLabel;
    @FXML private CheckBox settingsWakeToggle;
    private SoterIAFace face;
    private UserData currentUser;
    private ChatSession activeSession;

    private STT sttService;
    private TTS ttsService;
    private KnowledgeBase knowledgeBase;
    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();

    private ChatViewManager viewManager;
    private InferenceEngine inferenceEngine;
    private SessionCoordinator sessionCoordinator;
    private com.soteria.infrastructure.intelligence.kws.WakeWordService wakeWordService;
    private ChatInferenceUiBridge inferenceUi;
    private ProfileRepository profileRepository;

    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private final AtomicBoolean haltedAssistantOnPartial = new AtomicBoolean(false);
    private boolean ttsEnabled = true;
    private String currentLanguage = "English";
    /** Matches default {@code speechRate} in {@link com.soteria.infrastructure.intelligence.tts.SherpaTTSService}. */
    private float currentSpeechRate = 1.44f;
    private boolean wakeWordEnabled = true;
    private boolean syncingSettingsUi;

    private final AtomicLong inferenceGeneration = new AtomicLong(0);
    private final ChatTTSIdleChain ttsIdleChain = new ChatTTSIdleChain();
    private final ChatOutboundDedupe outboundDedupe = new ChatOutboundDedupe();

    @FXML
    private void initialize() {
        face = new SoterIAFace(85);
        faceHolder.getChildren().add(face);

        ChatViewManager.UIComponents ui = new ChatViewManager.UIComponents(
            chatMessages, chatScrollPane, chatSheet, subtitleLabel,
            partialTranscriptLabel, aiStatusLabel, statusDot
        );
        viewManager = new ChatViewManager(ui);
        sessionCoordinator = new SessionCoordinator(sessionList, historySidebar);

        viewManager.setSubtitle(PROMPT_READY);
        viewManager.setAiStatusPill("Preparando IA", STATUS_WARMING);
        setInputLocked(true);
        setupSettingsUi();
        handleNewChat();
    }

    public void init(UserData profile, BootstrapService bootstrap, ProfileRepository profiles) {
        this.currentUser = profile;
        this.profileRepository = profiles;
        if (profile.preferredLanguage() != null) {
            this.currentLanguage = profile.preferredLanguage();
        }

        viewManager.addBotMessage("Hola " + profile.fullName() + ". Soy SoterIA. "
                + "Pulsa el micro y cuéntame qué pasa, o escribe si no puedes hablar.");

        viewManager.setAiStatusPill("Cargando", STATUS_WARMING);

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
            this.inferenceUi = new ChatInferenceUiBridge(
                    viewManager,
                    sessionCoordinator,
                    face,
                    safetyContainer,
                    knowledgeBase,
                    ttsIdleChain,
                    logger,
                    PROMPT_READY,
                    STATUS_READY,
                    STATUS_ALERT,
                    () -> activeSession,
                    () -> ttsEnabled,
                    () -> ttsService);
            this.aiAvailable = true;

            if (this.ttsService != null) {
                this.ttsService.setLanguage(currentLanguage);
                this.ttsService.setSpeechRate(currentSpeechRate);
            }

            if (this.wakeWordService != null && wakeWordEnabled) {
                logger.log(Level.INFO, "[{0}] Registering wake-word listener.", instanceId);
                this.wakeWordService.startListening(this::onWakeWordDetected);
            }

            viewManager.setAiStatusPill("IA lista", STATUS_READY);
            viewManager.setSubtitle(PROMPT_READY);
            setInputLocked(false);
        }));
    }

    private void interruptOngoingGeneration() {
        inferenceGeneration.incrementAndGet();
        if (ttsService != null) {
            ttsService.stop();
        }
        if (inferenceEngine != null) {
            inferenceEngine.cancel();
        }
        if (inferenceUi != null) {
            inferenceUi.resetBotStreamState();
        }
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
        if (!outboundDedupe.tryAccept(message, logger, instanceId, "[{0}] Send ignored — duplicate rapid submit.")) {
            return;
        }

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
        haltedAssistantOnPartial.set(false);
        if (wakeWordService != null) {
            wakeWordService.stopListening();
        }
        micButton.getStyleClass().add("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-stop");
        face.setState(SoterIAFace.State.LISTENING);
        viewManager.setSubtitle("Escuchando…");
        viewManager.setPartialTranscript("");
        sttService.startListening(ChatSTTListenerFactory.create(new ChatSTTListenerFactory.Params(
                instanceId,
                logger,
                outboundDedupe,
                viewManager,
                face,
                haltedAssistantOnPartial,
                this::stopRecording,
                this::interruptOngoingGeneration,
                text -> {
                    stopRecording();
                    viewManager.addUserMessage(text);
                    processMessage(text);
                })));
    }

    private void stopRecording() {
        isRecording = false;
        if (sttService != null) {
            sttService.stopListening();
        }
        micButton.getStyleClass().remove("mic-fab-active");
        if (micIcon != null) micIcon.setIconLiteral("mdmz-mic");

        if (wakeWordService != null && wakeWordEnabled) {
            wakeWordService.startListening(this::onWakeWordDetected);
        }
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
        viewManager.setPartialTranscript(message);
        viewManager.showThinkingIndicator();

        new Thread(() -> {
            logger.log(Level.INFO, "[{0}] Starting inference thread.", instanceId);
            inferenceEngine.runInference(message, activeSession, currentUser, currentLanguage,
                    inferenceUi, inferenceGeneration, correlationId);
        }, "soteria-inference").start();
    }

    @FXML
    private void handleEmergencyButton() {
        handleEmergencyAlert("Botón SOS pulsado manualmente");
    }

    private void handleEmergencyAlert(String reason) {
        face.setState(SoterIAFace.State.ALERT);
        viewManager.setAiStatusPill("Alerta activa", STATUS_ALERT);
        viewManager.setSubtitle("Enviando alerta…");
        ChatEmergencyDispatch.start(
                reason,
                locationProvider,
                alertService,
                currentUser,
                new ChatEmergencyDispatch.Callbacks() {
                    @Override
                    public void onSuccess(String location) {
                        viewManager.addBotMessage("Alerta enviada. Ubicación detectada: " + location
                                + ". La ayuda está en camino — mantén la calma.");
                        viewManager.setSubtitle("Alerta enviada. Mantente en la línea.");
                    }

                    @Override
                    public void onSendFailed() {
                        viewManager.addBotMessage("No pude enviar la alerta automática. Llama al 112/911 ahora.");
                        viewManager.setSubtitle("Llama al 112 directamente.");
                        viewManager.setAiStatusPill("Alerta falló", STATUS_OFFLINE);
                    }

                    @Override
                    public void onDispatchError() {
                        viewManager.setSubtitle("Error al enviar alerta.");
                        viewManager.setAiStatusPill("Alerta falló", STATUS_OFFLINE);
                    }
                });
    }

    @FXML private void toggleChatSheet() { viewManager.toggleChatSheet(); }
    @FXML private void closeChatSheet() { viewManager.closeChatSheet(); }
    @FXML private void toggleHistorySidebar() { sessionCoordinator.toggleHistorySidebar(this::refreshSessionList); }

    @FXML
    private void handleNewChat() {
        this.activeSession = sessionCoordinator.startNewSession();
        viewManager.clearMessages();
        if (aiAvailable) viewManager.addBotMessage("Nueva sesión de emergencia iniciada. Dime qué sucede.");
        refreshSessionList();
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
        sessionCoordinator.refreshSessionList(activeSession, this::loadSession, this::handleSessionDeletedFromList);
    }

    private void handleSessionDeletedFromList(ChatSession deleted) {
        if (activeSession != null && deleted.getId().equals(activeSession.getId())) {
            handleNewChat();
        }
        refreshSessionList();
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

    private void refreshSettingsModelLabel() {
        if (settingsModelLabel == null || currentUser == null) {
            return;
        }
        String model = currentUser.preferredModel() != null ? currentUser.preferredModel() : "—";
        String url = currentUser.customModelUrl();
        if (url != null && !url.isBlank()) {
            settingsModelLabel.setText(model + "\n" + url);
        } else {
            settingsModelLabel.setText(model);
        }
    }

    private void setupSettingsUi() {
        settingsThemeCombo.getItems().setAll("Oscuro");
        settingsThemeCombo.getSelectionModel().selectFirst();
        settingsThemeCombo.setDisable(true);

        settingsLanguageCombo.getItems().setAll(OnboardingLanguageCatalog.SUPPORTED);
        settingsLanguageCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (syncingSettingsUi || newV == null) {
                return;
            }
            currentLanguage = newV;
            if (ttsService != null) {
                ttsService.setLanguage(newV);
            }
            persistProfileLanguage();
        });

        settingsSpeechRateSlider.setMin(0.5);
        settingsSpeechRateSlider.setMax(2.0);
        settingsSpeechRateSlider.setValue(currentSpeechRate);
        settingsSpeechRateSlider.setMajorTickUnit(0.5);
        settingsSpeechRateSlider.setMinorTickCount(0);
        settingsSpeechRateSlider.valueProperty().addListener((obs, oldV, newV) -> {
            float v = newV.floatValue();
            currentSpeechRate = v;
            if (settingsSpeechRateLabel != null) {
                settingsSpeechRateLabel.setText(String.format("%.2f×", v));
            }
            if (!syncingSettingsUi && ttsService != null) {
                ttsService.setSpeechRate(v);
            }
        });

        settingsWakeToggle.selectedProperty().addListener((obs, oldV, newV) -> {
            if (syncingSettingsUi) {
                return;
            }
            wakeWordEnabled = newV;
            applyWakeWordPreference();
        });
    }

    private void persistProfileLanguage() {
        if (profileRepository == null || currentUser == null) {
            return;
        }
        UserData updated = new UserData(
                currentUser.fullName(),
                currentUser.phoneNumber(),
                currentUser.gender(),
                currentUser.birthDate(),
                currentUser.medicalInfo(),
                currentUser.emergencyContact(),
                currentUser.preferredModel(),
                currentLanguage,
                currentUser.customModelUrl());
        try {
            profileRepository.save(updated);
            currentUser = updated;
        } catch (IOException e) {
            logger.log(Level.WARNING, "[{0}] Failed to save language preference", instanceId);
            logger.log(Level.FINE, "Profile save failed", e);
        }
    }

    private void applyWakeWordPreference() {
        if (wakeWordService == null) {
            return;
        }
        if (!wakeWordEnabled) {
            wakeWordService.stopListening();
        } else if (!isRecording) {
            wakeWordService.startListening(this::onWakeWordDetected);
        }
    }

    @FXML
    private void openSettingsFromSidebar() {
        sessionCoordinator.closeHistorySidebar();
        openSettingsOverlay();
    }

    private void openSettingsOverlay() {
        syncingSettingsUi = true;
        try {
            settingsLanguageCombo.setValue(OnboardingLanguageCatalog.matchOrDefault(currentLanguage));
            settingsSpeechRateSlider.setValue(currentSpeechRate);
            if (settingsSpeechRateLabel != null) {
                settingsSpeechRateLabel.setText(String.format("%.2f×", currentSpeechRate));
            }
            refreshSettingsModelLabel();
            boolean wakeAvailable = wakeWordService != null;
            settingsWakeToggle.setDisable(!wakeAvailable);
            settingsWakeToggle.setSelected(wakeAvailable && wakeWordEnabled);
        } finally {
            syncingSettingsUi = false;
        }
        settingsOverlay.setVisible(true);
        settingsOverlay.setManaged(true);
    }

    @FXML
    private void closeSettings() {
        settingsOverlay.setVisible(false);
        settingsOverlay.setManaged(false);
    }

    private void setInputLocked(boolean locked) {
        micButton.setDisable(locked);
        chatButton.setDisable(locked);
        if (sendButton != null) sendButton.setDisable(locked);
        if (messageInput != null) messageInput.setDisable(locked);
    }
}
