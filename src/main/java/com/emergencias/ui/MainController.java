package com.emergencias.ui;

import com.emergencias.alert.AlertSender;
import com.emergencias.alert.EmergencyLogger;
import com.emergencias.model.EmergencyEvent;
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
 Controlador principal del chat conversacional de emergencias.
 */
public class MainController implements Initializable {

    // Componentes del chat
    @FXML private VBox chatPanel;
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessages;
    @FXML private TextField messageInput;
    @FXML private Button voiceButton;
    @FXML private Button sendButton;
    @FXML private Label statusLabel;
    @FXML private Label aiStatusLabel;
    
    // Servicios
    private AIClassifierClient aiClient;
    private IAlert alertSender;
    private EmergencyLogger logger;
    private boolean aiAvailable = false;
    private boolean isRecording = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        aiClient = new AIClassifierClient("http://localhost:8000");
        alertSender = new AlertSender();
        logger = new EmergencyLogger();
        
        checkAIAvailability();
        
        addBotMessage("¡Hola! Soy tu asistente de emergencias. 🚨\n\n" +
                     "Puedo ayudarte a:\n" +
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
                    addBotMessage("⚠️ El servidor de IA no está disponible.\n" +
                                 "Puedo ayudarte en modo básico.\n\n" +
                                 "Para activar la IA, ejecuta:\n" +
                                 "cd python-backend && python -m uvicorn server:app --host 0.0.0.0 --port 8000");
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
            addBotMessage("❌ El reconocimiento de voz requiere el servidor de IA.\n" +
                         "Por favor, escribe tu emergencia.");
            return;
        }
        
        if (isRecording) return;
        
        isRecording = true;
        voiceButton.setText("⏹️");
        voiceButton.setStyle("-fx-background-color: #ef4444;");
        setStatus("🎤 Grabando... Habla ahora (5 segundos)");
        addBotMessage("🎤 Escuchando... Describe tu emergencia.");
        
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
                        addBotMessage("❌ No se pudo entender el audio. Intenta de nuevo o escribe tu emergencia.");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    isRecording = false;
                    voiceButton.setText("🎤");
                    voiceButton.setStyle("");
                    setStatus("Error en grabación");
                    addBotMessage("❌ Error al grabar: " + e.getMessage());
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
            System.err.println("Error en transcripción: " + e.getMessage());
            return null;
        }
    }

    private void processMessage(String message) {
        new Thread(() -> {
            try {
                if (aiAvailable) {
                    String jsonResponse = aiClient.classify(message);
                    Platform.runLater(() -> {
                        if (jsonResponse != null) {
                            processAIResponse(jsonResponse);
                        } else {
                            processManually(message);
                        }
                    });
                } else {
                    Platform.runLater(() -> processManually(message));
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    addBotMessage("❌ Error al procesar: " + e.getMessage());
                    setStatus("Error");
                });
            }
        }).start();
    }

    private void processAIResponse(String jsonResponse) {
        String[] emergencies = AIClassifierClient.extractEmergencies(jsonResponse);
        
        if (emergencies.length > 0) {
            String primaryEmergency = emergencies[0];
            String typeName = AIClassifierClient.extractString(primaryEmergency, "type_name");
            double confidence = AIClassifierClient.extractDouble(primaryEmergency, "confidence");
            String context = AIClassifierClient.extractString(primaryEmergency, "context");
            String[] instructions = AIClassifierClient.extractStringArray(primaryEmergency, "instructions");
            
            StringBuilder response = new StringBuilder();
            response.append("🔍 **Análisis completado**\n\n");
            response.append("Tipo: ").append(typeName).append("\n");
            response.append("Confianza: ").append(String.format("%.0f%%", confidence * 100)).append("\n");
            response.append("Contexto: ").append(context).append("\n\n");
            
            if (instructions.length > 0) {
                response.append("📋 **Instrucciones:**\n");
                for (String instruction : instructions) {
                    response.append("• ").append(instruction).append("\n");
                }
            }
            
            addBotMessage(response.toString());
            addBotMessage("¿Quieres que envíe una alerta al 112? (responde 'sí' o 'no')");
            setStatus("Esperando confirmación");
        } else {
            addBotMessage("No pude identificar una emergencia clara.\n" +
                         "Por favor, describe con más detalle lo que está pasando.");
            setStatus("Listo");
        }
    }

    private void processManually(String message) {
        String lowerMessage = message.toLowerCase();
        
        if (lowerMessage.contains("fuego") || lowerMessage.contains("incendio")) {
            addBotMessage("🔥 **Incendio detectado**\n\n" +
                         "Instrucciones:\n" +
                         "• Evacua la zona inmediatamente\n" +
                         "• Llama al 112\n" +
                         "• No uses ascensores\n\n" +
                         "¿Quieres que envíe una alerta al 112?");
        } else if (lowerMessage.contains("accidente") || lowerMessage.contains("coche")) {
            addBotMessage("🚗 **Accidente de tráfico detectado**\n\n" +
                         "Instrucciones:\n" +
                         "• Señaliza el lugar del accidente\n" +
                         "• No muevas a los heridos\n" +
                         "• Llama al 112\n\n" +
                         "¿Quieres que envíe una alerta al 112?");
        } else if (lowerMessage.contains("duele") || lowerMessage.contains("médico")) {
            addBotMessage("🏥 **Emergencia médica detectada**\n\n" +
                         "Instrucciones:\n" +
                         "• Mantén la calma\n" +
                         "• Siéntate o acuéstate\n" +
                         "• Llama al 112\n\n" +
                         "¿Quieres que envíe una alerta al 112?");
        } else if (lowerMessage.contains("sí") || lowerMessage.contains("si")) {
            sendEmergencyAlert();
        } else if (lowerMessage.contains("no")) {
            addBotMessage("✅ Entendido. Si necesitas ayuda más tarde, aquí estaré.");
        } else {
            addBotMessage("¿Podrías indicarme si es:\n" +
                         "• 🚗 Accidente de tráfico\n" +
                         "• 🏥 Problema médico\n" +
                         "• 🔥 Incendio\n" +
                         "• ⚔️ Agresión\n" +
                         "• 🌊 Desastre natural");
        }
        setStatus("Listo");
    }

    private void sendEmergencyAlert() {
        addBotMessage("📤 Enviando alerta al 112...");
        setStatus("Enviando alerta...");
        
        new Thread(() -> {
            try {
                EmergencyEvent event = new EmergencyEvent(
                    "Emergencia reportada por chat",
                    "Ubicación no especificada",
                    5,
                    "Reportada via chat"
                );
                
                // Registrar el evento en el logger
                logger.logEmergency(event);
                
                boolean sent = alertSender.send(event);
                
                Platform.runLater(() -> {
                    if (sent) {
                        addBotMessage("✅ **¡Alerta enviada con éxito!**\n\n" +
                                     "Se ha contactado al servicio de emergencias 112.\n" +
                                     "La ayuda está en camino.");
                        setStatus("✅ Alerta enviada");
                    } else {
                        addBotMessage("❌ No se pudo enviar la alerta.\n\n**Llama al 112 manualmente.**");
                        setStatus("❌ Error al enviar");
                    }
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
        String[] lines = message.split("\n");
        
        for (String line : lines) {
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
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }
}