package com.soteria.infrastructure.intelligence.tts;

import com.soteria.infrastructure.intelligence.system.LanguageUtils;

import java.text.Normalizer;

/**
 * Kokoro / sherpa-onnx can throw native C++ exceptions on odd Unicode (e.g. mojibake mixing Greek
 * or Latin romanization into zh); keep TTS input conservative per engine language.
 */
final class TtsTextSanitizer {

    private static final int MAX_CODE_POINTS = 480;

    private enum Mode {
        /** No script filtering beyond controls / length */
        PERMISSIVE,
        /** Japanese: kana + han + basic ASCII Latin */
        JA,
        /**
         * Chinese / Han-primary: only Han letters (no Latin/pinyin), plus punctuation and digits.
         * Applied when UI lang is zh or when the fragment already contains Han — stops Kokoro zh crashes
         * on mixed garbage regardless of a wrong {@code languageHint}.
         */
        ZH_HANZI_ONLY
    }

    private TtsTextSanitizer() {
    }

    static String sanitize(String s) {
        return sanitize(s, null);
    }

    static String sanitize(String s, String languageHint) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFC);
        String lang = "";
        if (languageHint != null && !languageHint.isBlank()) {
            lang = LanguageUtils.isoCode(languageHint);
        }

        Mode mode = resolveMode(lang, n);

        StringBuilder b = new StringBuilder(Math.min(n.length(), MAX_CODE_POINTS + 16));
        int count = 0;
        int i = 0;
        while (i < n.length() && count < MAX_CODE_POINTS) {
            int cp = n.codePointAt(i);
            int step = Character.charCount(cp);
            i += step;
            if (shouldKeepCodePoint(cp, mode)) {
                b.appendCodePoint(cp);
                count++;
            }
        }
        return b.toString().trim();
    }

    private static Mode resolveMode(String langIso, String normalizedText) {
        if ("ja".equals(langIso)) {
            return Mode.JA;
        }
        if ("zh".equals(langIso) || containsHan(normalizedText)) {
            return Mode.ZH_HANZI_ONLY;
        }
        return Mode.PERMISSIVE;
    }

    private static boolean containsHan(String s) {
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldKeepCodePoint(int cp, Mode mode) {
        if (cp == 0xFFFD) {
            return false;
        }
        int t = Character.getType(cp);
        if (t == Character.CONTROL && cp != '\n' && cp != '\r' && cp != '\t') {
            return false;
        }
        return mode == Mode.PERMISSIVE || isAllowed(cp, t, mode);
    }

    private static boolean isAllowed(int cp, int type, Mode mode) {
        if (isGreekOrGreekExtended(cp)) {
            return false;
        }
        if (isCombiningMark(type)) {
            return true;
        }
        if (Character.isWhitespace(cp) || Character.isDigit(cp)) {
            return true;
        }
        if (type == Character.LETTER_NUMBER) {
            return true;
        }
        if (Character.isLetter(cp)) {
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            return switch (mode) {
                case JA -> {
                    if (sc == Character.UnicodeScript.HIRAGANA
                            || sc == Character.UnicodeScript.KATAKANA
                            || sc == Character.UnicodeScript.HAN) {
                        yield true;
                    }
                    yield sc == Character.UnicodeScript.LATIN && isBasicLatinLetter(cp);
                }
                case ZH_HANZI_ONLY -> sc == Character.UnicodeScript.HAN;
                default -> true;
            };
        }
        return isPunctuationCategory(type);
    }

    private static boolean isBasicLatinLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    private static boolean isGreekOrGreekExtended(int cp) {
        return (cp >= 0x0370 && cp <= 0x03FF) || (cp >= 0x1F00 && cp <= 0x1FFF);
    }

    private static boolean isCombiningMark(int type) {
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isPunctuationCategory(int type) {
        return type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION;
    }
}
