package com.soteria.ui.onboarding;

import java.util.List;

/**
 * Display names for the onboarding language combo box and safe resolution from detected or persisted strings.
 */
final class OnboardingLanguageCatalog {

    static final String DEFAULT = "English";

    static final List<String> SUPPORTED = List.of(
            DEFAULT, "Spanish", "French", "German", "Italian", "Portuguese");

    private OnboardingLanguageCatalog() {
    }

    /**
     * Maps a locale or free-form language string to one of {@link #SUPPORTED}, case-insensitive.
     *
     * @param lang value from GPS heuristics, saved draft, or {@code null}
     * @return the matching supported label, or {@link #DEFAULT}
     */
    static String matchOrDefault(String lang) {
        if (lang == null) {
            return DEFAULT;
        }
        return SUPPORTED.stream()
                .filter(s -> s.equalsIgnoreCase(lang))
                .findFirst()
                .orElse(DEFAULT);
    }
}
