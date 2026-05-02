package com.soteria.ui.onboarding;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Display names for the onboarding language combo box and safe resolution from detected or persisted strings.
 */
public final class OnboardingLanguageCatalog {

    public static final String DEFAULT = "English";

    private static final String SPANISH = "Spanish";
    private static final String FRENCH = "French";
    private static final String GERMAN = "German";
    private static final String ITALIAN = "Italian";
    private static final String PORTUGUESE = "Portuguese";
    private static final String ROMANIAN = "Romanian";
    private static final String VALENCIAN = "Valencian";
    private static final String CHINESE = "Chinese";
    private static final String RUSSIAN = "Russian";
    private static final String ARABIC = "Arabic";
    private static final String JAPANESE = "Japanese";

    public static final List<String> SUPPORTED = List.of(
            DEFAULT,
            SPANISH,
            FRENCH,
            GERMAN,
            ITALIAN,
            PORTUGUESE,
            ROMANIAN,
            VALENCIAN,
            CHINESE,
            RUSSIAN,
            ARABIC,
            JAPANESE);

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        putAlias("en", DEFAULT);
        putAlias("english", DEFAULT);
        putAlias("es", SPANISH);
        putAlias("spanish", SPANISH);
        putAlias("español", SPANISH);
        putAlias("castellano", SPANISH);
        putAlias("fr", FRENCH);
        putAlias("french", FRENCH);
        putAlias("français", FRENCH);
        putAlias("francés", FRENCH);
        putAlias("de", GERMAN);
        putAlias("german", GERMAN);
        putAlias("deutsch", GERMAN);
        putAlias("it", ITALIAN);
        putAlias("italian", ITALIAN);
        putAlias("italiano", ITALIAN);
        putAlias("pt", PORTUGUESE);
        putAlias("portuguese", PORTUGUESE);
        putAlias("português", PORTUGUESE);
        putAlias("portugués", PORTUGUESE);
        putAlias("ro", ROMANIAN);
        putAlias("rum", ROMANIAN);
        putAlias("ron", ROMANIAN);
        putAlias("romanian", ROMANIAN);
        putAlias("română", ROMANIAN);
        putAlias("romana", ROMANIAN);
        putAlias("ca", VALENCIAN);
        putAlias("valencian", VALENCIAN);
        putAlias("valencià", VALENCIAN);
        putAlias("catalan", VALENCIAN);
        putAlias("català", VALENCIAN);
        putAlias("catala", VALENCIAN);
        putAlias("zh", CHINESE);
        putAlias("zh-cn", CHINESE);
        putAlias("zh_cn", CHINESE);
        putAlias("chinese", CHINESE);
        putAlias("mandarin", CHINESE);
        putAlias("中文", CHINESE);
        putAlias("ru", RUSSIAN);
        putAlias("rus", RUSSIAN);
        putAlias("russian", RUSSIAN);
        putAlias("ruso", RUSSIAN);
        putAlias("русский", RUSSIAN);
        putAlias("ar", ARABIC);
        putAlias("ara", ARABIC);
        putAlias("arabic", ARABIC);
        putAlias("árabe", ARABIC);
        putAlias("العربية", ARABIC);
        putAlias("ja", JAPANESE);
        putAlias("jpn", JAPANESE);
        putAlias("japanese", JAPANESE);
        putAlias("japonés", JAPANESE);
        putAlias("日本語", JAPANESE);
    }

    private static void putAlias(String key, String canonical) {
        ALIASES.put(key.toLowerCase(Locale.ROOT), canonical);
    }

    private OnboardingLanguageCatalog() {
    }

    /**
     * Maps a locale or free-form language string to one of {@link #SUPPORTED}, case-insensitive.
     *
     * @param lang value from GPS heuristics, saved draft, or {@code null}
     * @return the matching supported label, or {@link #DEFAULT}
     */
    public static String matchOrDefault(String lang) {
        if (lang == null || lang.isBlank()) {
            return DEFAULT;
        }
        String trimmed = lang.trim();
        for (String s : SUPPORTED) {
            if (s.equalsIgnoreCase(trimmed)) {
                return s;
            }
        }
        String mapped = ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
        if (mapped != null) {
            return mapped;
        }
        int sep = trimmed.indexOf('-');
        if (sep > 0) {
            String primary = trimmed.substring(0, sep).toLowerCase(Locale.ROOT);
            mapped = ALIASES.get(primary);
            if (mapped != null) {
                return mapped;
            }
        }
        sep = trimmed.indexOf('_');
        if (sep > 0) {
            String primary = trimmed.substring(0, sep).toLowerCase(Locale.ROOT);
            mapped = ALIASES.get(primary);
            if (mapped != null) {
                return mapped;
            }
        }
        return DEFAULT;
    }
}
