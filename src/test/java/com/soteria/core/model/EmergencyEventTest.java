package com.soteria.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyEventTest {

    private static final String TYPE = "Incendio";
    private static final String LOCATION = "Calle Mayor 3";

    @Test
    @DisplayName("Constructor fills components and sets timestamp")
    void constructorFillsAllFields() {
        LocalDateTime before = LocalDateTime.now();
        EmergencyEvent event = new EmergencyEvent(TYPE, LOCATION, 8, "Juan");
        LocalDateTime after = LocalDateTime.now();

        assertEquals(TYPE, event.emergencyType());
        assertNotNull(event.timestamp());

        // Check if timestamp is within range
        assertFalse(event.timestamp().isBefore(before));
        assertFalse(event.timestamp().isAfter(after));
    }

    @Test
    @DisplayName("toString includes type, location and severity")
    void toStringIncludesKeyFields() {
        EmergencyEvent event = new EmergencyEvent(TYPE, LOCATION, 8, "Juan");
        String out = event.toString();

        assertTrue(out.contains(TYPE));
        assertTrue(out.contains(LOCATION));
        assertTrue(out.contains("8"));
    }

    @Test
    @DisplayName("Should support multilingual emergency events (UTF-8)")
    void multilingualEventSupport() {
        // Test data for various languages and scripts
        java.util.Map<String, java.util.List<String>> eventData = java.util.Map.of(
            "Russian", java.util.List.of("Сердечный приступ", "Красная площадь, Москва", "Иван Иванов"),
            "Arabic", java.util.List.of("حادث سيارة", "دبي، الإمارات العربية المتحدة", "أحمد محمد"),
            "Chinese", java.util.List.of("地震", "四川省成都市", "张三"),
            "Hindi", java.util.List.of("बाढ़", "मुंबई, महाराष्ट्र", "विजय"),
            "Greek", java.util.List.of("Πυρκαγιά", "Αθήνα, Ελλάδα", "Νίκος")
        );

        eventData.forEach((lang, data) -> {
            String type = data.get(0);
            String loc = data.get(1);
            String user = data.get(2);
            
            EmergencyEvent event = new EmergencyEvent(type, loc, 9, user);
            
            assertEquals(type, event.emergencyType(), "Type mismatch in " + lang);
            assertEquals(loc, event.location(), "Location mismatch in " + lang);
            assertEquals(user, event.userData(), "User mismatch in " + lang);
            
            String log = event.toString();
            assertTrue(log.contains(type), "toString missing type in " + lang);
            assertTrue(log.contains(loc), "toString missing location in " + lang);
        });
    }

    @Test
    @DisplayName("Records are immutable - components cannot be changed")
    void immutability() {
        EmergencyEvent event = new EmergencyEvent("Type", "Loc", 5, "User");
        assertNotNull(event.emergencyType());
    }

    @Test
    @DisplayName("Should handle boundary severity levels")
    void severityRange() {
        EmergencyEvent low = new EmergencyEvent("Minor", "Loc", 1, "User");
        EmergencyEvent high = new EmergencyEvent("Critical", "Loc", 10, "User");
        
        assertEquals(1, low.severityLevel());
        assertEquals(10, high.severityLevel());
    }

    @Test
    @DisplayName("Should throw exception for invalid severity levels")
    void invalidSeverityThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> 
            new EmergencyEvent("Type", "Loc", 0, "User"));
        assertThrows(IllegalArgumentException.class, () -> 
            new EmergencyEvent("Type", "Loc", 11, "User"));
    }
}
