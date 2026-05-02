package com.soteria.ui.i18n;

import com.soteria.ui.onboarding.OnboardingLanguageCatalog;

import java.util.Locale;

/**
 * Maps persisted onboarding / settings language labels to JavaFX / ResourceBundle locales.
 *
 * <p>Resource files under {@code src/main/resources/i18n/}:
 * {@code messages.properties} (default English), {@code messages_es.properties},
 * {@code messages_fr.properties}, {@code messages_de.properties},
 * {@code messages_it.properties}, {@code messages_pt.properties},
 * {@code messages_ro.properties}, {@code messages_ca.properties} (Valencian UI strings; {@code ca} is the JVM bundle key),
 * {@code messages_zh_CN.properties}, {@code messages_ru.properties},
 * {@code messages_ar.properties}, {@code messages_ja.properties}.
 * {@link java.util.ResourceBundle} loads {@code messages_fr} for {@link java.util.Locale#FRENCH}, etc.
 */
public final class UiLocales {

    private UiLocales() {
    }

    /** English if {@code preferred} is null or blank. */
    public static Locale fromPreferredLanguage(String preferred) {
        String canonical = OnboardingLanguageCatalog.matchOrDefault(preferred);
        if ("Spanish".equals(canonical)) {
            return Locale.forLanguageTag("es");
        }
        if ("French".equals(canonical)) {
            return Locale.FRENCH;
        }
        if ("German".equals(canonical)) {
            return Locale.GERMAN;
        }
        if ("Italian".equals(canonical)) {
            return Locale.ITALIAN;
        }
        if ("Portuguese".equals(canonical)) {
            return Locale.forLanguageTag("pt");
        }
        if ("Romanian".equals(canonical)) {
            return Locale.forLanguageTag("ro");
        }
        if ("Valencian".equals(canonical)) {
            return Locale.forLanguageTag("ca-ES-valencia");
        }
        if ("Chinese".equals(canonical)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if ("Russian".equals(canonical)) {
            return Locale.forLanguageTag("ru");
        }
        if ("Arabic".equals(canonical)) {
            return Locale.forLanguageTag("ar");
        }
        if ("Japanese".equals(canonical)) {
            return Locale.JAPANESE;
        }
        return Locale.ENGLISH;
    }
}
