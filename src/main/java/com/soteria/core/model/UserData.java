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
    String customModelUrl
) {
    public static final String INCOMPLETE_NAME = "[INCOMPLETE]";

    public boolean isComplete() {
        return fullName != null && !fullName.equals(INCOMPLETE_NAME);
    }

    @Override
    public String toString() {
        return String.format(
            "Name: %s%nPhone: %s%nGender: %s%nBirthDate: %s%nEmergency Contact: %s%nMedical Info: %s",
            fullName, phoneNumber, gender, birthDate, emergencyContact, medicalInfo
        );
    }
}
