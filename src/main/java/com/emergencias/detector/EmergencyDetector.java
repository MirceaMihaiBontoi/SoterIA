package com.emergencias.detector;

import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import com.emergencias.services.AIClassifierClient;

/**
 * Clase encargada de detectar emergencias.
 * Versión refactorizada para funcionar con JavaFX (sin dependencias de consola).
 */
public class EmergencyDetector {
    private static final int MIN_SEVERITY = 1;
    private static final int MAX_SEVERITY = 10;

    private final UserData userData;
    private final AIClassifierClient aiClient;

    /**
     * Constructor para uso en la UI (JavaFX).
     */
    public EmergencyDetector(UserData userData, AIClassifierClient aiClient) {
        this.userData = userData;
        this.aiClient = aiClient;
    }

    /**
     * Clasifica un mensaje de emergencia.
     * Método principal para uso en la UI.
     */
    public DetectionResult classifyEmergency(String description) {
        if (aiClient != null && aiClient.isAvailable()) {
            return classifyWithAI(description);
        } else {
            return classifyManually(description);
        }
    }

    /**
     * Clasificación con IA.
     */
    private DetectionResult classifyWithAI(String description) {
        String jsonResponse = aiClient.classify(description);
        
        if (jsonResponse == null) {
            return classifyManually(description);
        }
        
        String corrected = AIClassifierClient.extractString(jsonResponse, "corrected_text");
        String[] emergencies = AIClassifierClient.extractEmergencies(jsonResponse);
        
        if (emergencies.length > 0) {
            String primaryEmergency = emergencies[0];
            String typeName = AIClassifierClient.extractString(primaryEmergency, "type_name");
            double confidence = AIClassifierClient.extractDouble(primaryEmergency, "confidence");
            String context = AIClassifierClient.extractString(primaryEmergency, "context");
            String[] instructions = AIClassifierClient.extractStringArray(primaryEmergency, "instructions");
            
            return new DetectionResult(true, typeName, context, confidence, instructions, corrected);
        }
        
        return classifyManually(description);
    }

    /**
     * Clasificación manual (fallback cuando IA no está disponible).
     */
    private DetectionResult classifyManually(String message) {
        String lower = message.toLowerCase();
        String typeName = null;
        String context = null;
        String[] instructions = new String[0];
        
        if (lower.contains("fuego") || lower.contains("incendio")) {
            typeName = "Incendio";
            context = "incendio";
            instructions = new String[]{
                "Evacua inmediatamente",
                "Llama al 112",
                "No uses ascensores"
            };
        } else if (lower.contains("accidente") || lower.contains("coche")) {
            typeName = "Accidente de tráfico";
            context = "accidente de tráfico";
            instructions = new String[]{
                "Señaliza el lugar",
                "No muevas heridos",
                "Llama al 112"
            };
        } else if (lower.contains("duele") || lower.contains("médico") || lower.contains("medico")) {
            typeName = "Problema médico";
            context = "emergencia médica";
            instructions = new String[]{
                "Mantén la calma",
                "Siéntate",
                "Llama al 112"
            };
        } else if (lower.contains("agresión") || lower.contains("agresion") || lower.contains("ataque")) {
            typeName = "Agresión";
            context = "agresión";
            instructions = new String[]{
                "Aléjate del agresor",
                "Busca un lugar seguro",
                "Llama al 112"
            };
        } else if (lower.contains("inundación") || lower.contains("inundacion") || lower.contains("terremoto")) {
            typeName = "Desastre natural";
            context = "desastre natural";
            instructions = new String[]{
                "Busca un lugar alto",
                "Aléjate de estructuras inestables",
                "Llama al 112"
            };
        }
        
        if (typeName != null) {
            return new DetectionResult(true, typeName, context, 0.0, instructions, message);
        }
        
        return new DetectionResult(false, null, null, 0.0, new String[0], message);
    }

    /**
     * Crea un EmergencyEvent a partir del resultado de detección.
     */
    public EmergencyEvent createEvent(DetectionResult result, String location, int severity) {
        if (location == null || location.isEmpty()) {
            location = "Ubicación no especificada";
        }
        
        return new EmergencyEvent(
            result.getTypeName(),
            location,
            severity,
            userData.toString()
        );
    }

    /**
     * Valida que el nivel de severidad sea correcto.
     */
    public boolean isValidSeverity(int severity) {
        return severity >= MIN_SEVERITY && severity <= MAX_SEVERITY;
    }
    
    /**
     * Obtiene el nivel de severidad mínimo.
     */
    public int getMinSeverity() {
        return MIN_SEVERITY;
    }
    
    /**
     * Obtiene el nivel de severidad máximo.
     */
    public int getMaxSeverity() {
        return MAX_SEVERITY;
    }

    // ========================================
    // CLASE INTERNA PARA RESULTADO
    // ========================================
    
    public static class DetectionResult {
        private final boolean detected;
        private final String typeName;
        private final String context;
        private final double confidence;
        private final String[] instructions;
        private final String correctedText;
        
        public DetectionResult(boolean detected, String typeName, String context, 
                               double confidence, String[] instructions, String correctedText) {
            this.detected = detected;
            this.typeName = typeName;
            this.context = context;
            this.confidence = confidence;
            this.instructions = instructions;
            this.correctedText = correctedText;
        }
        
        public boolean isDetected() { return detected; }
        public String getTypeName() { return typeName; }
        public String getContext() { return context; }
        public double getConfidence() { return confidence; }
        public String[] getInstructions() { return instructions; }
        public String getCorrectedText() { return correctedText; }
    }
}
