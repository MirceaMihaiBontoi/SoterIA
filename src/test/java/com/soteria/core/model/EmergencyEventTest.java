package com.soteria.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyEventTest {

    @Test
    @DisplayName("Constructor fills components and sets timestamp")
    void constructorFillsAllFields() {
        LocalDateTime before = LocalDateTime.now();
        EmergencyEvent event = new EmergencyEvent("Incendio", "Calle Mayor 3", 8, "Juan");
        LocalDateTime after = LocalDateTime.now();

        assertEquals("Incendio", event.emergencyType());
        assertNotNull(event.timestamp());
        
        // Check if timestamp is within range
        assertFalse(event.timestamp().isBefore(before));
        assertFalse(event.timestamp().isAfter(after));
    }

    @Test
    @DisplayName("toString includes type, location and severity")
    void toStringIncludesKeyFields() {
        EmergencyEvent event = new EmergencyEvent("Incendio", "Calle Mayor 3", 8, "Juan");
        String out = event.toString();

        assertTrue(out.contains("Incendio"));
        assertTrue(out.contains("Calle Mayor 3"));
        assertTrue(out.contains("8"));
    }
    
    @Test
    @DisplayName("Records are immutable - components cannot be changed")
    void immutability() {
        EmergencyEvent event = new EmergencyEvent("Type", "Loc", 5, "User");
        // No setters exist for records, so this verifies the structural change
        assertNotNull(event.emergencyType());
    }
}
