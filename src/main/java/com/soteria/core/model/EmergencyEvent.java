package com.soteria.core.model;

import java.time.LocalDateTime;
import java.util.Objects;

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
     * Compact constructor for validation.
     */
    public EmergencyEvent {
        Objects.requireNonNull(emergencyType, "Emergency type cannot be null");
        Objects.requireNonNull(location, "Location cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        Objects.requireNonNull(userData, "User data cannot be null");
        
        if (severityLevel < 1 || severityLevel > 10) {
            throw new IllegalArgumentException("Severity level must be between 1 and 10, got: " + severityLevel);
        }
    }

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
