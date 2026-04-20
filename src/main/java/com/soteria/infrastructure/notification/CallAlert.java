package com.soteria.infrastructure.notification;

import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;
import com.soteria.core.interfaces.AlertService;
import java.io.FileWriter;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of AlertService for standard phone alerts.
 * Demonstrates the use of interfaces and Strategy pattern implementation.
 */
public class CallAlert implements AlertService {
    private static final Logger log = Logger.getLogger(CallAlert.class.getName());
    
    private static final String EMERGENCY_NUMBER = "112";
    private static final String ALERTS_FILE = "logs/emergency_alerts.log";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public boolean send(EmergencyEvent event) {
        if (event == null) {
            log.severe("❌ Error: Cannot send a null alert");
            return false;
        }

        String alertMessage = formatAlertMessage(event);
        
        log.info(() -> "\n=== CALL ALERT SENT ===\n" + alertMessage);
        
        try (FileWriter writer = new FileWriter(ALERTS_FILE, true)) {
            writer.write("-".repeat(80) + "\n");
            writer.write("[CALL] " + alertMessage + "\n");
            writer.flush();
        } catch (IOException e) {
            log.log(Level.SEVERE, "❌ Error saving alert: {0}", e.getMessage());
            return false;
        }
        
        return simulateEmergencyCall(event);
    }

    @Override
    public void notifyContacts(UserData userData, EmergencyEvent event) {
        log.info("Notifying contacts via call...");
        if (userData == null || userData.emergencyContact() == null || userData.emergencyContact().isEmpty()) {
            log.warning("⚠️ No emergency contacts configured.");
            return;
        }
        
        log.log(Level.INFO, "✅ Call sent to contact: {0}", userData.emergencyContact());
    }

    @Override
    public String getAlertType() {
        return "Phone Call";
    }

    private String formatAlertMessage(EmergencyEvent event) {
        return String.format(
            "[%s] EMERGENCY ALERT\nType: %s\nLocation: %s\nSeverity: %d/10",
            event.timestamp().format(TIMESTAMP_FORMAT),
            event.emergencyType(),
            event.location(),
            event.severityLevel()
        );
    }

    private boolean simulateEmergencyCall(EmergencyEvent event) {
        log.log(Level.INFO, "Connecting to {0}...", EMERGENCY_NUMBER);
        try {
            Thread.sleep(1500); // Simulate connection delay
            log.log(Level.INFO, "✅ Connection established! Emergency: {0}", event.emergencyType());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(Level.SEVERE, "Call simulation interrupted: {0}", e.getMessage());
            return false;
        }
    }
}
