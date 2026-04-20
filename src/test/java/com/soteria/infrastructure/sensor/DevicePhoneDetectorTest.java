package com.soteria.infrastructure.sensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DevicePhoneDetector Tests")
class DevicePhoneDetectorTest {

    @Test
    @DisplayName("Should detect current OS without crashing")
    void detectCurrentSystem() {
        assertNotNull(DevicePhoneDetector.detect());
    }

    @Test
    @DisplayName("Should return UNKNOWN for Android (Bridge pending)")
    void detectAndroid() {
        assertEquals(DevicePhoneDetector.UNKNOWN, DevicePhoneDetector.detect("Android"));
    }

    @Test
    @DisplayName("Should return UNKNOWN for unsupported OS (Linux)")
    void detectLinux() {
        assertEquals(DevicePhoneDetector.UNKNOWN, DevicePhoneDetector.detect("Linux"));
    }

    @Test
    @DisplayName("Should return UNKNOWN for empty OS name")
    void detectEmpty() {
        assertEquals(DevicePhoneDetector.UNKNOWN, DevicePhoneDetector.detect(""));
    }

    @Test
    @DisplayName("UNKNOWN constant should be exported")
    void constantExported() {
        assertEquals("Unknown", DevicePhoneDetector.UNKNOWN);
    }
}
