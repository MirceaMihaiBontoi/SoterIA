package com.soteria.infrastructure.intelligence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

    @Test
    @DisplayName("getContent formatea correctamente el texto para la IA")
    void getContentFormatsText() {
        Protocol p = new Protocol();
        p.setTitle("RCP");
        p.setCategory("Cardiovascular");
        p.setSteps(List.of("Check responsiveness", "Call 112", "Start compressions"));
        
        String content = p.getContent();
        assertTrue(content.contains("Protocol: RCP"));
        assertTrue(content.contains("Check responsiveness. Call 112. Start compressions"));
    }
}
