package com.soteria.core.port;

import java.util.Locale;

/**
 * Port for localization and internationalization services.
 * Supports >50 languages for system messages and triage categories.
 */
public interface LocalizationService {
    /**
     * Translates a key to the current system locale.
     */
    String getMessage(String key);

    /**
     * Translates a key to a specific locale.
     */
    String getMessage(String key, Locale locale);

    /**
     * Translates a key with arguments.
     */
    String formatMessage(String key, Object... args);

    /**
     * Gets the current system locale.
     */
    Locale getCurrentLocale();

    /**
     * Sets the system locale.
     */
    void setLocale(Locale locale);
}
