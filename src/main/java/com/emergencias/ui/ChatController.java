package com.emergencias.ui;

import com.emergencias.alert.AlertSender;
import com.emergencias.alert.EmergencyLogger;
import com.emergencias.detector.EmergencyDetector;
import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import com.emergencias.services.AIClassifierClient;
import com.emergencias.services.IAlert;
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
 Controlador para la pantalla de chat conversacional.
 */
public class ChatController implements Initializable {

    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button voiceButton;
    @FXML private Label statusLabel;
    @FXML private Label aiStatusLabel;
    
    private AIClassifierClient aiClient;
    private IAlert alertSender;
    private EmergencyLogger logger;
    private EmergencyDetector detector; // ← USAR COMPONENTE EXISTENTE
    private UserData currentUser;
    private boolean aiAvailable = false;
    private boolean isRecording = false;
    private EmergencyDetector.DetectionResult lastDetectionResult; // ← GUARDAR ÚLTIMO RESULTADO

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        aiClient = new AIClassifierClient("http://localhost:8000");
        alertSender = new AlertSender();
        logger = new EmergencyLogger();
        checkAIAvailability();
    }
    
    public void setUserData(UserData userData) {
        this.currentUser = userData;
        this.detector = new EmergencyDetector(userData, aiClient); // ← INICIALIZAR DETECTOR
        
        // Mensaje de bienvenida personalizado
        addBotMessage("¡Hola " + userData.getFullName() + "! 👋\n\n" +
                     "Soy tu asistente de emergencias. Puedo ayudarte a:\n" +
                     "• Clasificar emergencias por texto o voz\n" +
                     "• Dar instrucciones de actuación\n" +
                     "• Enviar alertas al 112\n\n" +
                     "Describe lo que está pasando o presiona 🎤 para hablar.");
    }

    private void checkAIAvailability() {
        new Thread(() -> {
            aiAvailable = aiClient.isAvailable();
            Platform.runLater(() -> {
                if (aiAvailable) {
                    aiStatusLabel.setText("IA: ✅ Conectada");
                    aiStatusLabel.setStyle("-fx-text-fill: #10b981;");
                } else {
                    aiStatusLabel.setText("IA: ❌ Desconectada");
                    aiStatusLabel.setStyle("-fx-text-fill: #ef4444;");
                    addBotMessage("⚠️ Servidor de IA no disponible.\nModo básico activado.\n\n" +
                                 "Para activar IA completa:\ncd python-backend && python -m uvicorn server:app --host 0.0.0.0 --port 8000");
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
        setStatus("Procesando...");
        processMessage(message);
    }

    @FXML
    private void handleVoiceInput() {
        if (!aiAvailable) {
            addBotMessage("❌ Reconocimiento de voz requiere servidor de IA.\nPor favor escribe tu emergencia.");
            return;
        }
        
        if (isRecording) return;
        
        isRecording = true;
        voiceButton.setText("⏹️");
        voiceButton.setStyle("-fx-background-color: #ef4444;");
        setStatus("🎤 Grabando... (5 seg)");
        addBotMessage("🎤 Escuchando...");
        
        new Thread(() -> {
            try {
                String text = recordAndTranscribe();
                Platform.runLater(() -> {
                    isRecording = false;
                    voiceButton.setText("🎤");
                    voiceButton.setStyle("");
                    setStatus("Listo");
                    
                    if (text != null && !text.isEmpty()) {
                        addUserMessage(text);
                        processMessage(text);
                    } else {
                        addBotMessage("❌ No se entendió. Intenta de nuevo o escribe.");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    isRecording = false;
                    voiceButton.setText("🎤");
                    voiceButton.setStyle("");
                    setStatus("Error");
                    addBotMessage("❌ Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private String recordAndTranscribe() {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:8000/transcribe?duration=5"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));

            String body = response.body();
            if (body.contains("\"error\"")) return null;
            return AIClassifierClient.extractString(body, "text");
        } catch (Exception e) {
            return null;
        }
    }

    private void processMessage(String message) {
        new Thread(() -> {
            try {
                // Verificar si es una confirmación de alerta
                String lower = message.toLowerCase().trim();
                if (lastDetectionResult != null && (lower.equals("sí") || lower.equals("si"))) {
                    Platform.runLater(() -> sendEmergencyAlert());
                    return;
                }
                
                if (lastDetectionResult != null && lower.equals("no")) {
                    Platform.runLater(() -> {
                        addBotMessage("✅ Entendido. La alerta no se enviará.\n\nSi necesitas ayuda con otra emergencia, describe lo que está pasando.");
                        lastDetectionResult = null;
                        setStatus("Listo");
                    });
                    return;
                }
                
                // USAR MÉTODO PÚBLICO DE EMERGENCYDETECTOR
                EmergencyDetector.DetectionResult result = detector.classifyEmergency(message);
                
                Platform.runLater(() -> {
                    if (result.isDetected()) {
                        displayDetectionResult(result);
                        askForAlertConfirmation(result);
                    } else {
                        addBotMessage("No pude identificar la emergencia.\nDescribe con más detalle o elige:\n• 🚗 Accidente\n• 🏥 Médica\n• 🔥 Incendio\n• ⚔️ Agresión\n• 🌊 Desastre natural");
                    }
                    setStatus("Listo");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    addBotMessage("❌ Error: " + e.getMessage());
                    setStatus("Error");
                });
            }
        }).start();
    }
    
    private void displayDetectionResult(EmergencyDetector.DetectionResult result) {
        StringBuilder response = new StringBuilder();
        response.append("🔍 **Análisis completado**\n\n");
        response.append("Tipo: ").append(result.getTypeName()).append("\n");
        
        if (result.getConfidence() > 0) {
            response.append("Confianza: ").append(String.format("%.0f%%", result.getConfidence() * 100)).append("\n");
        }
        
        response.append("Contexto: ").append(result.getContext()).append("\n\n");
        
        String[] instructions = result.getInstructions();
        if (instructions.length > 0) {
            response.append("📋 **Instrucciones:**\n");
            for (String instruction : instructions) {
                response.append("• ").append(instruction).append("\n");
            }
        }
        
        addBotMessage(response.toString());
    }
    
    private void askForAlertConfirmation(EmergencyDetector.DetectionResult result) {
        this.lastDetectionResult = result;
        addBotMessage("¿Enviar alerta al 112? (sí/no)");
        setStatus("Esperando confirmación");
    }

    private void sendEmergencyAlert() {
        if (lastDetectionResult == null) {
            addBotMessage("❌ No hay emergencia detectada para enviar.");
            return;
        }
        
        addBotMessage("📤 Enviando alerta...");
        setStatus("Enviando...");
        
        new Thread(() -> {
            try {
                // USAR MÉTODO DE EMERGENCYDETECTOR PARA CREAR EVENTO
                EmergencyEvent event = detector.createEvent(lastDetectionResult, null, 5);
                
                // Registrar el evento en el logger
                logger.logEmergency(event);
                
                boolean sent = alertSender.send(event);
                
                Platform.runLater(() -> {
                    if (sent) {
                        addBotMessage("✅ **¡Alerta enviada al 112!**\n\nAyuda en camino.\nMantén la calma.");
                        setStatus("✅ Alerta enviada");
                    } else {
                        addBotMessage("❌ Error al enviar.\n**Llama al 112 manualmente.**");
                        setStatus("❌ Error");
                    }
                    lastDetectionResult = null; // Limpiar resultado
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    addBotMessage("❌ Error: " + e.getMessage());
                    setStatus("Error");
                });
            }
        }).start();
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
        for (String line : message.split("\n")) {
            Text text = new Text(line + "\n");
            text.setStyle("-fx-fill: #1e293b; -fx-font-size: 14;");
            textFlow.getChildren().add(text);
        }
        
        bubble.getChildren().add(textFlow);
        messageBox.getChildren().add(bubble);
        chatMessages.getChildren().add(messageBox);
        scrollToBottom();
    }

    private void scrollToBottom() {
        // Usar un pequeño retraso para asegurar que el layout se complete antes del scroll
        new Thread(() -> {
            try {
                Thread.sleep(50); // Pequeña pausa para que el layout se actualice
                Platform.runLater(() -> {
                    chatScrollPane.layout();
                    chatScrollPane.setVvalue(1.0);
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
            }
        }).start();
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }
}