package com.soteria.ui.chat;

import com.soteria.core.port.AlertService;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.LocalizationService;
import com.soteria.core.port.LocationProvider;
import com.soteria.core.port.STT;
import com.soteria.core.port.TTS;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.domain.chat.ChatSession;
import com.soteria.infrastructure.notification.NotificationAlertService;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.i18n.UiLocales;
import com.soteria.ui.view.SoterIAFace;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.persistence.ProfileRepository;
import com.soteria.ui.view.ChatViewManager;
import com.soteria.application.chat.InferenceEngine;
import com.soteria.ui.view.SessionCoordinator;

import com.soteria.ui.onboarding.OnboardingLanguageCatalog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
import java.text.MessageFormat;
import java.util.ResourceBundle;
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
    private static final String STATUS_READY = "ready";
    private static final String STATUS_WARMING = "warming";
    private static final String STATUS_OFFLINE = "offline";
    private static final String STATUS_ALERT = "alert";
    /** Bundle key last shown on the AI status pill (kept in sync when runtime language changes). */
    private volatile String trackedAiPillKey = "chat.status.preparing_ai";
    private volatile String trackedAiPillDot = STATUS_WARMING;
    /** Bundle key: idle mic hint (also used after AI becomes ready). */
    private static final String KEY_SUBTITLE_READY = "chat.subtitle.ready";
    private static final String KEY_SUBTITLE_LISTENING = "chat.subtitle.listening";

    @FXML
    private ResourceBundle resources;
    @FXML private StackPane faceHolder;
    @FXML private Label brandLabel;
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
    @FXML private Label chatSheetTitleLabel;
    @FXML private Label historyTitleLabel;
    @FXML private Button sidebarSettingsButton;
    @FXML private Button sidebarNewEmergencyButton;
    @FXML private Label settingsHeaderTitleLabel;
    @FXML private Label settingsThemeSectionLabel;
    @FXML private Label settingsLanguageSectionLabel;
    @FXML private Label settingsSpeechSectionLabel;
    @FXML private Label settingsModelSectionLabel;
    @FXML private Label settingsWakeSectionLabel;

    private SoterIAFace face;
    private UserData currentUser;
    private ChatSession activeSession;

    private STT sttService;
    private TTS ttsService;
    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();

    private ChatViewManager viewManager;
    private InferenceEngine inferenceEngine;
    private SessionCoordinator sessionCoordinator;
    private com.soteria.infrastructure.intelligence.kws.WakeWordService wakeWordService;
    private ChatInferenceUiBridge inferenceUi;
    private ProfileRepository profileRepository;
    private LocalizationService localization;

    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private final AtomicBoolean haltedAssistantOnPartial = new AtomicBoolean(false);
    private boolean ttsEnabled = true;
    private String currentLanguage = "English";
    /** Matches default {@code speechRate} in {@link com.soteria.infrastructure.intelligence.tts.SherpaTTSService}. */
    private float currentSpeechRate = 1.44f;
    private boolean wakeWordEnabled = true;

    private static final String SETTINGS_SYNC_PROP = "soteria.settingsUiSync";

    private AtomicBoolean settingsSyncFlag() {
        var props = settingsOverlay.getProperties();
        Object v = props.get(SETTINGS_SYNC_PROP);
        if (v instanceof AtomicBoolean existing) {
            return existing;
        }
        AtomicBoolean created = new AtomicBoolean(false);
        props.put(SETTINGS_SYNC_PROP, created);
        return created;
    }

    /** Used for the history sidebar session row (date = second row in UI). */
    private static final DateTimeFormatter SESSION_LIST_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private void stampSessionListTitle() {
        if (activeSession == null) {
            return;
        }
        String date = LocalDateTime.now().format(SESSION_LIST_DATE);
        if (localization != null) {
            activeSession.setTitle(localization.formatMessage("chat.session.list_title", date));
        } else if (resources != null) {
            activeSession.setTitle(MessageFormat.format(resources.getString("chat.session.list_title"), date));
        } else {
            activeSession.setTitle(date);
        }
        sessionCoordinator.saveCurrentSession();
    }

    private String profileDisplayNameForWelcome(UserData profile) {
        String n = profile.fullName();
        if (n == null || n.isBlank() || UserData.INCOMPLETE_NAME.equals(n)) {
            if (localization != null) {
                return localization.getMessage("chat.welcome.default_name");
            }
            if (resources != null) {
                return resources.getString("chat.welcome.default_name");
            }
            return "friend";
        }
        return n;
    }

    private void injectWelcomeMessage(UserData profile) {
        String welcome = localization.formatMessage("chat.welcome", profileDisplayNameForWelcome(profile));
        viewManager.addBotMessage(welcome);
        activeSession.addMessage(ChatMessage.model(welcome));
        sessionCoordinator.saveCurrentSession();
    }

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

        viewManager.setSubtitle(msg(KEY_SUBTITLE_READY));
        setAiStatusI18n("chat.status.preparing_ai", STATUS_WARMING);
        setInputLocked(true);
        setupSettingsUi();
        handleNewChat();
    }

    public void init(UserData profile, BootstrapService bootstrap, ProfileRepository profiles) {
        this.localization = bootstrap.localizationService();
        this.localization.setLocale(UiLocales.fromPreferredLanguage(profile.preferredLanguage()));
        this.sessionCoordinator.setLocalizationService(localization);
        applyChatChromeI18n();

        this.currentUser = profile;
        this.profileRepository = profiles;
        if (profile.preferredLanguage() != null) {
            this.currentLanguage = profile.preferredLanguage();
        }

        stampSessionListTitle();
        injectWelcomeMessage(profile);
        refreshSessionList();

        setAiStatusI18n("chat.status.loading", STATUS_WARMING);

        String initialStatus = bootstrap.statusProperty().get();
        if (initialStatus != null && !initialStatus.isBlank()) {
            viewManager.setSubtitle(initialStatus);
        }

        bootstrap.statusProperty().addListener((obs, old, nw) -> {
            if (!aiAvailable && nw != null && !nw.isBlank()) {
                viewManager.setSubtitle(nw);
            }
        });

        bootstrap.ready().whenComplete((ok, err) -> Platform.runLater(() -> applyBootstrapResult(bootstrap, err)));
    }

    private void applyBootstrapResult(BootstrapService bootstrap, Throwable err) {
        if (err != null) {
            logger.log(Level.SEVERE, "[{0}] Bootstrap failed: {1}", new Object[]{instanceId, err.getMessage()});
            setAiStatusI18n("chat.status.ai_offline", STATUS_OFFLINE);
            viewManager.setSubtitle(msg("chat.subtitle.bootstrap_offline"));
            face.setState(SoterIAFace.State.IDLE);
            return;
        }
        logger.log(Level.INFO, "[{0}] ready() complete. Configuring services...", instanceId);
        this.sttService = bootstrap.sttService();
        this.ttsService = bootstrap.ttsService();
        KnowledgeBase kb = bootstrap.knowledgeBase();
        this.inferenceEngine = new InferenceEngine(bootstrap.triageService(), bootstrap.brainService(), kb);
        this.wakeWordService = bootstrap.wakeWordService();
        this.inferenceUi = new ChatInferenceUiBridge(
                viewManager,
                sessionCoordinator,
                face,
                safetyContainer,
                kb,
                ttsIdleChain,
                logger,
                () -> msg(KEY_SUBTITLE_READY),
                STATUS_READY,
                STATUS_ALERT,
                this::setAiStatusI18n,
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

        setAiStatusI18n("chat.status.ai_ready", STATUS_READY);
        viewManager.setSubtitle(msg(KEY_SUBTITLE_READY));
        setInputLocked(false);
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
            viewManager.setSubtitle(msg("chat.subtitle.ai_warming"));
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
            viewManager.setSubtitle(msg("chat.subtitle.stt_unavailable"));
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
        viewManager.setSubtitle(msg(KEY_SUBTITLE_LISTENING));
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
                },
                () -> msg(KEY_SUBTITLE_LISTENING),
                t -> msg("chat.error.mic") + ": " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())
        )));
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
        viewManager.setSubtitle(msg("chat.subtitle.thinking"));
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
        handleEmergencyAlert(msg("chat.alert.reason_manual"));
    }

    private void handleEmergencyAlert(String reason) {
        face.setState(SoterIAFace.State.ALERT);
        setAiStatusI18n("chat.status.alert_active", STATUS_ALERT);
        viewManager.setSubtitle(msg("chat.alert.sending"));
        ChatEmergencyDispatch.start(
                reason,
                locationProvider,
                alertService,
                currentUser,
                new ChatEmergencyDispatch.Callbacks() {
                    @Override
                    public void onSuccess(String location) {
                        viewManager.addBotMessage(localization.formatMessage("chat.alert.sent_ok", location));
                        viewManager.setSubtitle(msg("chat.alert.sent_subtitle"));
                    }

                    @Override
                    public void onSendFailed() {
                        viewManager.addBotMessage(msg("chat.alert.send_failed"));
                        viewManager.setSubtitle(msg("chat.alert.call_direct"));
                        setAiStatusI18n("chat.status.alert_failed", STATUS_OFFLINE);
                    }

                    @Override
                    public void onDispatchError() {
                        viewManager.setSubtitle(msg("chat.alert.dispatch_error_subtitle"));
                        setAiStatusI18n("chat.status.alert_failed", STATUS_OFFLINE);
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
        stampSessionListTitle();
        if (aiAvailable) {
            viewManager.addBotMessage(msg("chat.session.new"));
            activeSession.addMessage(ChatMessage.model(msg("chat.session.new")));
            sessionCoordinator.saveCurrentSession();
        }
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
        settingsThemeCombo.getItems().setAll(resources.getString("ui.settings.theme.dark"));
        settingsThemeCombo.getSelectionModel().selectFirst();
        settingsThemeCombo.setDisable(true);

        settingsLanguageCombo.getItems().setAll(OnboardingLanguageCatalog.SUPPORTED);
        settingsLanguageCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (settingsSyncFlag().get() || newV == null) {
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
            if (!settingsSyncFlag().get() && ttsService != null) {
                ttsService.setSpeechRate(v);
            }
        });

        settingsWakeToggle.selectedProperty().addListener((obs, oldV, newV) -> {
            if (settingsSyncFlag().get()) {
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
            if (localization != null) {
                localization.setLocale(UiLocales.fromPreferredLanguage(currentLanguage));
                applyChatChromeI18n();
                refreshSessionList();
            }
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
        AtomicBoolean syncing = settingsSyncFlag();
        syncing.set(true);
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
            syncing.set(false);
        }
        settingsOverlay.setVisible(true);
        settingsOverlay.setManaged(true);
    }

    @FXML
    private void closeSettings() {
        settingsOverlay.setVisible(false);
        settingsOverlay.setManaged(false);
    }

    private void setAiStatusI18n(String messageKey, String dotClass) {
        trackedAiPillKey = messageKey;
        trackedAiPillDot = dotClass;
        viewManager.setAiStatusPill(msg(messageKey), dotClass);
    }

    /** Re-applies AI pill + subtitle strings that depend on {@link #localization} (e.g. after runtime language change). */
    private void refreshLiveHudForLocale() {
        if (viewManager == null || face == null) {
            return;
        }
        viewManager.setAiStatusPill(msg(trackedAiPillKey), trackedAiPillDot);
        SoterIAFace.State fs = face.getState();
        if (fs == SoterIAFace.State.SPEAKING || fs == SoterIAFace.State.ALERT) {
            return;
        }
        if (fs == SoterIAFace.State.IDLE && !isRecording) {
            viewManager.setSubtitle(msg(KEY_SUBTITLE_READY));
        } else if (fs == SoterIAFace.State.LISTENING) {
            viewManager.setSubtitle(msg(KEY_SUBTITLE_LISTENING));
        } else if (fs == SoterIAFace.State.THINKING) {
            viewManager.setSubtitle(msg("chat.subtitle.thinking"));
        }
    }

    private String msg(String key) {
        if (localization != null) {
            return localization.getMessage(key);
        }
        return resources != null ? resources.getString(key) : key;
    }

    private void applyChatChromeI18n() {
        if (localization == null) {
            return;
        }
        if (brandLabel != null) {
            brandLabel.setText(localization.getMessage("ui.chat.brand"));
        }
        if (chatSheetTitleLabel != null) {
            chatSheetTitleLabel.setText(localization.getMessage("ui.chat.sheet.title"));
        }
        if (historyTitleLabel != null) {
            historyTitleLabel.setText(localization.getMessage("ui.chat.history.title"));
        }
        if (sidebarSettingsButton != null) {
            sidebarSettingsButton.setText(localization.getMessage("ui.chat.history.settings"));
        }
        if (sidebarNewEmergencyButton != null) {
            sidebarNewEmergencyButton.setText(localization.getMessage("ui.chat.history.new_emergency"));
        }
        if (messageInput != null) {
            messageInput.setPromptText(localization.getMessage("ui.chat.input.prompt"));
        }
        if (settingsHeaderTitleLabel != null) {
            settingsHeaderTitleLabel.setText(localization.getMessage("ui.settings.title"));
        }
        if (settingsThemeSectionLabel != null) {
            settingsThemeSectionLabel.setText(localization.getMessage("ui.settings.theme"));
        }
        if (settingsLanguageSectionLabel != null) {
            settingsLanguageSectionLabel.setText(localization.getMessage("ui.settings.language"));
        }
        if (settingsSpeechSectionLabel != null) {
            settingsSpeechSectionLabel.setText(localization.getMessage("ui.settings.speech_rate"));
        }
        if (settingsModelSectionLabel != null) {
            settingsModelSectionLabel.setText(localization.getMessage("ui.settings.ai_model"));
        }
        if (settingsWakeSectionLabel != null) {
            settingsWakeSectionLabel.setText(localization.getMessage("ui.settings.wake"));
        }
        if (settingsWakeToggle != null) {
            settingsWakeToggle.setText(localization.getMessage("ui.settings.wake.checkbox"));
        }
        if (settingsThemeCombo != null) {
            settingsThemeCombo.getItems().setAll(localization.getMessage("ui.settings.theme.dark"));
            settingsThemeCombo.getSelectionModel().selectFirst();
        }
        refreshLiveHudForLocale();
    }

    private void setInputLocked(boolean locked) {
        micButton.setDisable(locked);
        chatButton.setDisable(locked);
        if (sendButton != null) sendButton.setDisable(locked);
        if (messageInput != null) messageInput.setDisable(locked);
    }
}
