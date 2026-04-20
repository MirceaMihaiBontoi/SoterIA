package com.soteria.infrastructure.notification;

import com.soteria.core.interfaces.AlertService;
import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;

import java.util.logging.Logger;

/**
 * Implementation of AlertService that handles notifications via multiple channels.
 */
public class NotificationAlertService implements AlertService {
    
    private static final Logger log = Logger.getLogger(NotificationAlertService.class.getName());
    
    private final AlertSender alertSender;
    private final CallAlert callAlert;

    public NotificationAlertService() {
        this.alertSender = new AlertSender();
        this.callAlert = new CallAlert();
    }

    @Override
    public boolean send(EmergencyEvent event) {
        log.info("SoterIA Notification: Dispatching multi-channel emergency alert...");
        
        // Use the implementations directly
        boolean alertSent = alertSender.send(event);
        boolean callInitiated = callAlert.send(event);
        
        return alertSent || callInitiated;
    }

    @Override
    public void notifyContacts(UserData userData, EmergencyEvent event) {
        log.info("SoterIA Notification: Notifying emergency contacts...");
        alertSender.notifyContacts(userData, event);
        callAlert.notifyContacts(userData, event);
    }

    @Override
    public String getAlertType() {
        return "Multi-Channel Notification (SMS/Call)";
    }
}
