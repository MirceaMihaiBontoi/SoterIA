package com.soteria.infrastructure.sensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemGPSLocationTest {

    @Test
    @DisplayName("detectPrimaryLanguage should return a valid language even without GPS")
    void detectPrimaryLanguageReturnsDefault() {
        SystemGPSLocation gps = new SystemGPSLocation();
        String lang = gps.detectPrimaryLanguage();
        assertTrue("Spanish".equals(lang) || "English".equals(lang));
    }

    @Test
    @DisplayName("getLocationDescription should handle unknown results gracefully")
    void getLocationDescriptionHandlesUnknown() {
        SystemGPSLocation gps = new SystemGPSLocation();
        String desc = gps.getLocationDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }
}
