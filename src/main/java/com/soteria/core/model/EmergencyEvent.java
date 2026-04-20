package com.soteria.core.model;

import java.time.LocalDateTime;

/**
 * Record representing an emergency event in the system.
 */
public record EmergencyEvent(
    String emergencyType,
    String location,
    int severityLevel,
    LocalDateTime timestamp,
    String userData
) {
    /**
     * Canonical constructor with default timestamp if needed.
     */
    public EmergencyEvent(String emergencyType, String location, int severityLevel, String userData) {
        this(emergencyType, location, severityLevel, LocalDateTime.now(), userData);
    }

    @Override
    public String toString() {
        return String.format(
            "[%s] Emergency: %s%nLocation: %s%nSeverity: %d%nUser: %s",
            timestamp, emergencyType, location, severityLevel, userData
        );
    }
}
