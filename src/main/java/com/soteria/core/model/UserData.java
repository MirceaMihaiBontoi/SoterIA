package com.soteria.core.model;

/**
 * Record containing the user's personal data for emergency situations.
 */
public record UserData(
    String fullName,
    String phoneNumber,
    String medicalInfo,
    String emergencyContact
) {
    @Override
    public String toString() {
        return String.format(
            "Name: %s%nPhone: %s%nEmergency Contact: %s%nMedical Info: %s",
            fullName, phoneNumber, emergencyContact, medicalInfo
        );
    }
}
