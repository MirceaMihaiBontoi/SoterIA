package com.soteria.core.model;

/**
 * Record containing the user's personal data for emergency situations.
 */
public record UserData(
    String fullName,
    String phoneNumber,
    String gender,
    String birthDate,
    String medicalInfo,
    String emergencyContact,
    String preferredModel,
    String preferredLanguage,
    /** Optional; when null, UI uses default TTS speech rate (~1.44). */
    Float ttsSpeechRate,
    /** Optional; when null, wake word defaults to on. */
    Boolean wakeWordEnabled
) {
    public static final String INCOMPLETE_NAME = "[INCOMPLETE]";

    public float effectiveTtsSpeechRate() {
        float v = ttsSpeechRate != null ? ttsSpeechRate : 1.44f;
        return Math.clamp(v, 0.5f, 2.0f);
    }

    public boolean effectiveWakeWordEnabled() {
        return wakeWordEnabled == null || wakeWordEnabled;
    }

    public boolean isComplete() {
        return fullName != null && !fullName.equals(INCOMPLETE_NAME) 
                && phoneNumber != null && !phoneNumber.isBlank()
                && medicalInfo != null && !medicalInfo.isBlank();
    }

    @Override
    public String toString() {
        return String.format(
            "Name: %s%nPhone: %s%nGender: %s%nBirthDate: %s%nEmergency Contact: %s%nMedical Info: %s%n" +
            "Language: %s%nModel: %s%nTTS rate: %s%nWake word: %s",
            fullName, phoneNumber, gender, birthDate, emergencyContact, medicalInfo,
            preferredLanguage, preferredModel,
            ttsSpeechRate != null ? ttsSpeechRate : "default",
            wakeWordEnabled != null ? wakeWordEnabled : "default"
        );
    }
}
