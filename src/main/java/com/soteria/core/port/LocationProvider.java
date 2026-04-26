package com.soteria.core.port;

/**
 * Interface that defines the contract for location/GPS services.
 * Allows retrieving the user's current location securely.
 */
public interface LocationProvider {
    String getCoordinates();
    boolean hasLocationPermission();
    boolean requestPermission();
    String getLocationDescription();
}
