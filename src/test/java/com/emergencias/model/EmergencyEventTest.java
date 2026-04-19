package com.emergencias.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyEventTest {

    @Test
    @DisplayName("Constructor rellena los campos y pone timestamp al momento de creación")
    void constructorFillsAllFields() {
        LocalDateTime before = LocalDateTime.now();
        EmergencyEvent event = new EmergencyEvent("Incendio", "Calle Mayor 3", 8, "Juan");
        LocalDateTime after = LocalDateTime.now();

        assertEquals("Incendio", event.getEmergencyType());
        assertEquals("Calle Mayor 3", event.getLocation());
        assertEquals(8, event.getSeverityLevel());
        assertEquals("Juan", event.getUserData());
        assertNotNull(event.getTimestamp());
        assertFalse(event.getTimestamp().isBefore(before));
        assertFalse(event.getTimestamp().isAfter(after));
        assertTrue(Duration.between(before, event.getTimestamp()).toSeconds() <= 5);
    }

    @Test
    @DisplayName("Setters modifican los campos mutables")
    void settersMutateFields() {
        EmergencyEvent event = new EmergencyEvent("Incendio", "X", 1, "U");
        event.setEmergencyType("Agresión");
        event.setLocation("Plaza 1");
        event.setSeverityLevel(6);
        event.setUserData("Pepe");

        assertEquals("Agresión", event.getEmergencyType());
        assertEquals("Plaza 1", event.getLocation());
        assertEquals(6, event.getSeverityLevel());
        assertEquals("Pepe", event.getUserData());
    }

    @Test
    @DisplayName("toString contiene el tipo, la ubicación y la gravedad")
    void toStringIncludesKeyFields() {
        EmergencyEvent event = new EmergencyEvent("Incendio", "Calle Mayor 3", 8, "Juan");
        String out = event.toString();

        assertTrue(out.contains("Incendio"));
        assertTrue(out.contains("Calle Mayor 3"));
        assertTrue(out.contains("8"));
    }
}
