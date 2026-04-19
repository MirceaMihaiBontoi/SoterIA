package com.emergencias.detector;

import com.emergencias.detector.EmergencyDetector.DetectionResult;
import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyDetectorTest {

    private EmergencyDetector detector;

    @BeforeEach
    void setUp() {
        UserData user = new UserData("Test User", "600123456", "Ninguna", "Familiar 600000000");
        detector = new EmergencyDetector(user, null);
    }

    @Test
    @DisplayName("Fallback manual clasifica incendios por palabras clave")
    void classifiesFireFromKeywords() {
        DetectionResult fuego = detector.classifyEmergency("hay fuego en la cocina");
        DetectionResult incendio = detector.classifyEmergency("un INCENDIO enorme");

        assertTrue(fuego.isDetected());
        assertEquals("Incendio", fuego.getTypeName());
        assertTrue(incendio.isDetected());
        assertEquals("Incendio", incendio.getTypeName());
        assertTrue(fuego.getInstructions().length > 0);
    }

    @Test
    @DisplayName("Fallback manual clasifica accidentes, médicos, agresiones y desastres")
    void classifiesOtherCategories() {
        assertEquals("Accidente de tráfico",
                detector.classifyEmergency("he tenido un accidente con el coche").getTypeName());
        assertEquals("Problema médico",
                detector.classifyEmergency("me duele mucho el pecho").getTypeName());
        assertEquals("Agresión",
                detector.classifyEmergency("sufrí una agresion en la calle").getTypeName());
        assertEquals("Desastre natural",
                detector.classifyEmergency("se ha producido un terremoto").getTypeName());
    }

    @Test
    @DisplayName("Fallback manual no detecta nada si no hay palabras clave")
    void returnsNotDetectedForUnknownText() {
        DetectionResult result = detector.classifyEmergency("hola, solo estoy probando la app");

        assertFalse(result.isDetected());
        assertNull(result.getTypeName());
        assertEquals(0, result.getInstructions().length);
        assertEquals(0.0, result.getConfidence());
    }

    @Test
    @DisplayName("isValidSeverity respeta los límites [1,10]")
    void validatesSeverityBounds() {
        assertFalse(detector.isValidSeverity(0));
        assertTrue(detector.isValidSeverity(1));
        assertTrue(detector.isValidSeverity(5));
        assertTrue(detector.isValidSeverity(10));
        assertFalse(detector.isValidSeverity(11));
        assertFalse(detector.isValidSeverity(-3));
    }

    @Test
    @DisplayName("createEvent rellena ubicación por defecto cuando está vacía")
    void createEventDefaultsLocation() {
        DetectionResult result = detector.classifyEmergency("hay fuego");
        EmergencyEvent eventEmpty = detector.createEvent(result, "", 5);
        EmergencyEvent eventNull = detector.createEvent(result, null, 5);
        EmergencyEvent eventReal = detector.createEvent(result, "Calle Mayor 3", 7);

        assertEquals("Ubicación no especificada", eventEmpty.getLocation());
        assertEquals("Ubicación no especificada", eventNull.getLocation());
        assertEquals("Calle Mayor 3", eventReal.getLocation());
        assertEquals("Incendio", eventReal.getEmergencyType());
        assertEquals(7, eventReal.getSeverityLevel());
    }
}
