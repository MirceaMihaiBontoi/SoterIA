package com.soteria.ui.chat;

import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;
import com.soteria.core.port.AlertService;
import com.soteria.core.port.LocationProvider;

import javafx.application.Platform;

/**
 * Runs SOS-style alert HTTP/send work off the JavaFX thread; callbacks are marshalled with {@link Platform#runLater(Runnable)}.
 */
final class ChatEmergencyDispatch {

    interface Callbacks {
        void onSuccess(String location);

        void onSendFailed();

        void onDispatchError();
    }

    private ChatEmergencyDispatch() {
    }

    static void start(
            String reason,
            LocationProvider locationProvider,
            AlertService alertService,
            UserData currentUser,
            Callbacks callbacks) {
        new Thread(() -> {
            try {
                String location = locationProvider.getLocationDescription();
                EmergencyEvent event = new EmergencyEvent(
                        "EMERGENCY: " + reason,
                        location,
                        10,
                        currentUser != null ? currentUser.fullName() : "Usuario desconocido");

                boolean success = alertService.send(event);
                Platform.runLater(() -> {
                    if (success) {
                        callbacks.onSuccess(location);
                    } else {
                        callbacks.onSendFailed();
                    }
                });
            } catch (Exception _) {
                Platform.runLater(callbacks::onDispatchError);
            }
        }, "soteria-alert").start();
    }
}
