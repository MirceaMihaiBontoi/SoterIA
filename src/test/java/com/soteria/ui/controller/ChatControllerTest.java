package com.soteria.ui.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("ChatController UI Integration Tests")
class ChatControllerTest {

    @Test
    @DisplayName("Controller should be instantiable (Headless)")
    void testInstantiation() {
        // ChatController requires JavaFX Toolkit for full initialization (@FXML)
        // This is a placeholder for UI automation tests (TestFX)
        ChatController controller = new ChatController();
        assertNotNull(controller);
    }
    
    /* 
     * NOTE: Logic tests previously here were moved to InferenceEngineTest.java 
     * to respect the decoupling between UI and Application layers.
     */
}
