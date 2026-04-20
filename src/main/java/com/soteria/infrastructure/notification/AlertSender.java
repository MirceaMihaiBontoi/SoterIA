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
 * Class responsible for sending emergency notifications to the corresponding services.
 * Implements the AlertService interface, allowing for polymorphism and easy extension.
 */
public class AlertSender implements AlertService {
    private static final Logger log = Logger.getLogger(AlertSender.class.getName());
    
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
        
        log.info(() -> "\n=== ALERT SENT ===\n" + alertMessage);
        
        try (FileWriter writer = new FileWriter(ALERTS_FILE, true)) {
            writer.write("-".repeat(80) + "\n");
            writer.write(alertMessage + "\n");
            writer.flush();
        } catch (IOException e) {
            log.log(Level.SEVERE, "❌ Error saving alert to file: {0}", e.getMessage());
            return false;
        }
        
        return simulateEmergencyServiceCall(event);
    }

    @Override
    public void notifyContacts(UserData userData, EmergencyEvent event) {
        log.info("Notifying emergency contacts...");
        
        if (userData == null || userData.emergencyContact() == null || userData.emergencyContact().isEmpty()) {
            log.warning("⚠️ No emergency contacts configured.");
            return;
        }
        
        if (log.isLoggable(Level.INFO)) {
            log.info(() -> String.format("A notification has been sent to emergency contacts:%n" +
                                       "Emergency Type: %s%n" +
                                       "Location: %s%n" +
                                       "Event Time: %s",
                                       event.emergencyType(),
                                       event.location(),
                                       event.timestamp().format(TIMESTAMP_FORMAT)));
        }
    }

    @Override
    public String getAlertType() {
        return "Emergency Alert System";
    }

    private String formatAlertMessage(EmergencyEvent event) {
        return String.format(
            "[%s] EMERGENCY ALERT\n" +
            "Type: %s\n" +
            "Location: %s\n" +
            "Severity Level: %d/10\n" +
            "Event Time: %s\n" +
            "\nUSER INFORMATION:\n%s",
            event.timestamp().format(TIMESTAMP_FORMAT),
            event.emergencyType(),
            event.location(),
            event.severityLevel(),
            event.timestamp().format(TIMESTAMP_FORMAT),
            event.userData()
        );
    }

    private boolean simulateEmergencyServiceCall(EmergencyEvent event) {
        log.log(Level.INFO, "Connecting to emergency service {0}...", EMERGENCY_NUMBER);
        
        try {
            Thread.sleep(1500); // Simulate connection delay
            
            if (log.isLoggable(Level.INFO)) {
                log.info(() -> String.format("Connection established!%n" +
                                           "Operator: What is your emergency?%n" +
                                           "System: An emergency of type %s has been detected.%n" +
                                           "Location: %s%n" +
                                           "Help is on the way!",
                                           event.emergencyType(),
                                           event.location()));
            }
            
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(Level.SEVERE, "Error connecting to emergency services: {0}", e.getMessage());
            return false;
        }
    }
}
