package com.emergencias.alert;

import com.emergencias.model.EmergencyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyLoggerTest {

    @Test
    @DisplayName("logEmergency devuelve un UUID válido para un evento válido")
    void logEmergencyReturnsValidUuid() {
        EmergencyLogger logger = new EmergencyLogger();
        EmergencyEvent event = new EmergencyEvent("Incendio", "Calle Test " + System.nanoTime(), 4, "TestUser");

        String id = logger.logEmergency(event);

        assertNotNull(id);
        assertDoesNotThrow(() -> UUID.fromString(id));
    }

    @Test
    @DisplayName("logEmergency rechaza eventos null con IllegalArgumentException")
    void logEmergencyRejectsNull() {
        EmergencyLogger logger = new EmergencyLogger();
        assertThrows(IllegalArgumentException.class, () -> logger.logEmergency(null));
    }
}
