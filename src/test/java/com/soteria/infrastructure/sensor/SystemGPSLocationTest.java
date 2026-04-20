package com.soteria.infrastructure.sensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemGPSLocationTest {

    @Test
    @DisplayName("detectPrimaryLanguage devuelve un idioma válido incluso sin GPS")
    void detectPrimaryLanguageReturnsDefault() {
        SystemGPSLocation gps = new SystemGPSLocation();
        String lang = gps.detectPrimaryLanguage();
        assertTrue("Spanish".equals(lang) || "English".equals(lang));
    }

    @Test
    @DisplayName("getLocationDescription maneja resultados desconocidos")
    void getLocationDescriptionHandlesUnknown() {
        SystemGPSLocation gps = new SystemGPSLocation();
        String desc = gps.getLocationDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }
}
