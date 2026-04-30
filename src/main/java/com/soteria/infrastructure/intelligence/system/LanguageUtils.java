package com.soteria.infrastructure.intelligence.system;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for language code normalization and mapping.
 * Primarily used to convert human-readable names (e.g. "Spanish") to ISO codes (e.g. "es").
 */
public class LanguageUtils {
    private static final Logger logger = Logger.getLogger(LanguageUtils.class.getName());

    private LanguageUtils() {
        throw new IllegalStateException("Utility class");
    }

    private static final Map<String, String> LANG_CODE_MAP = Map.ofEntries(
            Map.entry("spanish", "es"),
            Map.entry("español", "es"),
            Map.entry("castellano", "es"),
            Map.entry("english", "en"),
            Map.entry("inglés", "en"),
            Map.entry("french", "fr"),
            Map.entry("français", "fr"),
            Map.entry("francés", "fr"),
            Map.entry("italian", "it"),
            Map.entry("italiano", "it"),
            Map.entry("portuguese", "pt"),
            Map.entry("português", "pt"),
            Map.entry("portugués", "pt")
    );

    /**
     * Normalizes a language name or code to a 2-letter ISO-639-1 code.
     *
     * @param lang The language name (e.g. "Spanish", "Español") or code (e.g. "es").
     * @return 2-letter ISO code, or empty string if it should be auto-detected by the engine.
     */
    public static String isoCode(String lang) {
        if (lang == null || lang.isBlank()) {
            return "";
        }

        String lower = lang.toLowerCase().trim();

        // 1. Check direct map
        if (LANG_CODE_MAP.containsKey(lower)) {
            return LANG_CODE_MAP.get(lower);
        }

        // 2. If it's already a 2-letter code, return it
        if (lower.length() == 2) {
            return lower;
        }

        // 3. Try to match using Java Locales
        for (Locale locale : Locale.getAvailableLocales()) {
            if (lower.equalsIgnoreCase(locale.getDisplayLanguage(Locale.ENGLISH)) ||
                lower.equalsIgnoreCase(locale.getDisplayLanguage(locale)) ||
                lower.equalsIgnoreCase(locale.getDisplayLanguage(Locale.forLanguageTag("es")))) {
                return locale.getLanguage();
            }
        }

        logger.log(Level.WARNING, "Could not resolve ISO code for language: {0}. Defaulting to auto-detection (empty).", lang);
        return ""; 
    }
}
