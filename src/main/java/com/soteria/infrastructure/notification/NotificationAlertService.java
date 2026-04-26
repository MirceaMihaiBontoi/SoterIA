package com.soteria.infrastructure.notification;

import com.soteria.core.port.AlertService;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes emergency events to the local alerts log and simulates the handoff
 * to emergency services. Real SMS/call integration will replace the simulation
 * in a later phase; the log is the durable record until then.
 */
public class NotificationAlertService implements AlertService {

    private static final Logger log = Logger.getLogger(NotificationAlertService.class.getName());
    private final Path alertsFile;
    private static final String EMERGENCY_NUMBER = "112";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long SIMULATED_CALL_MS = 1500;

    public NotificationAlertService() {
        this(Paths.get("logs", "emergency_alerts.log"));
    }

    /**
     * Internal constructor for testing.
     */
    NotificationAlertService(Path alertsFile) {
        this.alertsFile = alertsFile;
    }

    @Override
    public boolean send(EmergencyEvent event) {
        if (event == null) {
            log.severe("Cannot send a null alert");
            return false;
        }

        String message = formatMessage(event);
        log.info(() -> "\n=== ALERT ===\n" + message);

        if (!persist(message)) {
            return false;
        }

        return simulateCall(event);
    }

    @Override
    public void notifyContacts(UserData userData, EmergencyEvent event) {
        if (userData == null || userData.emergencyContact() == null || userData.emergencyContact().isEmpty()) {
            log.warning("No emergency contacts configured.");
            return;
        }
        log.log(Level.INFO, "Notification dispatched to contact: {0}", userData.emergencyContact());
    }

    @Override
    public String getAlertType() {
        return "Local Log + Simulated Call";
    }

    private String formatMessage(EmergencyEvent event) {
        return String.format(
                "[%s] EMERGENCY ALERT%nType: %s%nLocation: %s%nSeverity: %d/10%nUser: %s",
                event.timestamp().format(TIMESTAMP),
                event.emergencyType(),
                event.location(),
                event.severityLevel(),
                event.userData());
    }

    private boolean persist(String message) {
        try {
            if (alertsFile.getParent() != null) {
                Files.createDirectories(alertsFile.getParent());
            }
            String entry = "-".repeat(80) + System.lineSeparator() + message + System.lineSeparator();
            Files.writeString(alertsFile, entry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to persist alert", e);
            return false;
        }
    }

    private boolean simulateCall(EmergencyEvent event) {
        log.log(Level.INFO, "Connecting to {0}...", EMERGENCY_NUMBER);
        try {
            Thread.sleep(SIMULATED_CALL_MS);
            log.log(Level.INFO, "Connection established. Emergency: {0}", event.emergencyType());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(Level.WARNING, "Call simulation interrupted", e);
            return false;
        }
    }
}
