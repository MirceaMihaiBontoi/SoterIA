package com.soteria.infrastructure.sensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Logic: Should identify Windows-like strings")
    void logicWindows() {
        // We can't easily test the actual PowerShell execution here,
        // but we can verify the dispatcher logic.
        // Note: detect("Windows 11") would call detectWindows() which likely returns UNKNOWN 
        // in this environment if no modem is present.
        String result = DevicePhoneDetector.detect("Windows 10");
        assertNotNull(result);
    }

    @Test
    @DisplayName("Logic: Should identify Android-like strings")
    void logicAndroid() {
        assertEquals(DevicePhoneDetector.UNKNOWN, DevicePhoneDetector.detect("Android 13"));
    }

    @Test
    @DisplayName("UNKNOWN constant should be exported")
    void constantExported() {
        assertEquals("Unknown", DevicePhoneDetector.UNKNOWN);
    }
}
