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

import java.util.ArrayList;
import java.util.List;

public class ChatController {

    private static final int MAX_HISTORY_TURNS = 10;
    private static final String PROMPT_READY = "Pulsa el micro para hablar";

    @FXML private StackPane faceHolder;
    @FXML private Label subtitleLabel;
    @FXML private Label partialTranscriptLabel;
    @FXML private Label aiStatusLabel;
    @FXML private Circle statusDot;
    @FXML private Button micButton;
    @FXML private Button alertButton;
    @FXML private Button chatButton;
    @FXML private VBox chatSheet;
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button sendButton;

    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final LocationProvider locationProvider = new SystemGPSLocation();
    private final AlertService alertService = new NotificationAlertService();

    private SoterIAFace face;
    private UserData currentUser;

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
        setAiStatusPill("Preparando IA", "warming");
        setInputLocked(true);
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

        setAiStatusPill("Cargando", "warming");
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
                setAiStatusPill("IA offline", "offline");
                setSubtitle("No puedo escucharte ahora. Usa el botón SOS si es urgente.");
                face.setState(SoterIAFace.State.IDLE);
                return;
            }
            this.sttService = bootstrap.sttService();
            this.brainService = bootstrap.brainService();
            this.knowledgeBase = bootstrap.knowledgeBase();
            this.aiAvailable = true;
            setAiStatusPill("IA lista", "ready");
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
            micButton.setText("⏹");
            face.setState(SoterIAFace.State.LISTENING);
            setSubtitle("Escuchando…");
            setPartialTranscript("");
            sttService.startListening(new STTListener() {
                @Override public void onResult(String text) {
                    if (!text.isEmpty()) {
                        Platform.runLater(() -> {
                            stopRecordingUI();
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
        micButton.setText("🎙");
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

        conversationHistory.add(ChatMessage.user(message));
        trimHistory();

        face.setState(SoterIAFace.State.THINKING);
        setSubtitle("Pensando…");
        List<ChatMessage> snapshot = List.copyOf(conversationHistory);
        new Thread(() -> {
            List<Protocol> results = knowledgeBase.findProtocols(message);
            String context = results.isEmpty() ? "No specific protocol matched." : results.get(0).getContent();
            String response = brainService.generateResponse(snapshot, context, currentLanguage);
            Platform.runLater(() -> {
                conversationHistory.add(ChatMessage.model(response));
                trimHistory();
                addBotMessage(response);
                face.setState(SoterIAFace.State.IDLE);
                setSubtitle(PROMPT_READY);
            });
        }, "soteria-inference").start();
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_TURNS) {
            conversationHistory.remove(0);
        }
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

    private void setSubtitle(String text) {
        Platform.runLater(() -> subtitleLabel.setText(text));
    }

    private void setPartialTranscript(String text) {
        Platform.runLater(() -> partialTranscriptLabel.setText(text == null ? "" : text));
    }

    private void setAiStatusPill(String text, String dotClass) {
        Platform.runLater(() -> {
            aiStatusLabel.setText(text);
            statusDot.getStyleClass().removeAll("ready", "warming", "offline", "alert");
            statusDot.getStyleClass().add(dotClass);
        });
    }

    private boolean isEmergencyCommand(String message) {
        String msg = message.toLowerCase();
        return msg.contains("112") || msg.contains("alert") || msg.contains("emergency")
                || msg.contains("help") || msg.contains("ambulance");
    }

    private void handleEmergencyAlert(String message) {
        face.setState(SoterIAFace.State.ALERT);
        setAiStatusPill("Alerta activa", "alert");
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
                        setAiStatusPill("Alerta falló", "offline");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setSubtitle("Error al enviar alerta.");
                    setAiStatusPill("Alerta falló", "offline");
                });
            }
        }, "soteria-alert").start();
    }
}
