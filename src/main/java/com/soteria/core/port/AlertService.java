package com.soteria.core.port;

import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;

/**
 * Interface that defines the contract for sending emergency alerts.
 * Implements the Strategy pattern to allow different forms of notification.
 */
public interface AlertService {
    /**
     * Sends an emergency alert.
     */
    boolean send(EmergencyEvent event);
    
    /**
     * Notifies the user's emergency contacts.
     */
    void notifyContacts(UserData userData, EmergencyEvent event);
    
    /**
     * Gets the alert type.
     */
    String getAlertType();
}
