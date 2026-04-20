package com.soteria.infrastructure.sensor;

import com.soteria.core.interfaces.LocationProvider;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simulated implementation of LocationProvider.
 * In a real application, it would integrate with device GPS APIs.
 */
public class SystemGPSLocation implements LocationProvider {
    private static final Logger log = Logger.getLogger(SystemGPSLocation.class.getName());
    
    private boolean hasPermission = false;
    private static final String DEFAULT_COORDINATES = "40.4168° N, 3.7038° W";
    private static final String DEFAULT_LOCATION = "Plaza Mayor, Madrid";
    private static final String UNKNOWN_LOCATION = "Unknown";

    @Override
    public String getCoordinates() {
        if (!hasPermission) {
            log.warning("⚠️ Location permission not granted. Using default location.");
            return UNKNOWN_LOCATION;
        }
        return DEFAULT_COORDINATES;
    }

    @Override
    public boolean hasLocationPermission() {
        return hasPermission;
    }

    @Override
    public boolean requestPermission() {
        log.info("📍 Requesting location permission...");
        this.hasPermission = true;
        log.info("✅ Location permission granted.");
        return true;
    }

    @Override
    public String getLocationDescription() {
        if (!hasPermission) {
            requestPermission();
        }
        return DEFAULT_LOCATION;
    }
}
