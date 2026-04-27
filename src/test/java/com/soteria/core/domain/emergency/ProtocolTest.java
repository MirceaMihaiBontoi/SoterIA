package com.soteria.core.domain.emergency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

    @Test
    @DisplayName("getContent should format text correctly for LLM context")
    void getContentFormatsText() {
        Protocol p = new Protocol();
        p.setTitle("CPR");
        p.setSteps(List.of("Step 1", "Step 2"));
        
        String content = p.getContent();
        assertTrue(content.contains("Protocol: CPR"));
        assertTrue(content.contains("Step 1. Step 2"));
    }

    @Test
    @DisplayName("Should handle multilingual protocols correctly (UTF-8)")
    void multilingualProtocolIntegrity() {
        Protocol p = new Protocol();
        
        // Russian
        p.setTitle("СЛР (Сердечно-легочная реанимация)");
        p.setKeywords(List.of("сердце", "дыхание", "помощь"));
        p.setSteps(List.of("Проверьте сознание", "Вызовите 112"));
        
        String content = p.getContent();
        assertTrue(content.contains("СЛР"));
        assertTrue(content.contains("Проверьте сознание"));
        assertEquals(3, p.getKeywords().size());

        // Arabic
        p.setTitle("الإنعاش القلبي الرئوي");
        p.setKeywords(List.of("قلب", "تنفس", "إسعاف"));
        assertTrue(p.getContent().contains("الإنعاش"));

        // Chinese
        p.setTitle("心肺复苏术");
        p.setKeywords(List.of("心脏", "呼吸", "急救"));
        assertTrue(p.getContent().contains("心肺复苏术"));

        // Hindi
        p.setTitle("सीपीआर (कार्डियोपल्मोनरी पुनर्जीवन)");
        p.setKeywords(List.of("दिल", "साँस", "मदद"));
        assertTrue(p.getContent().contains("सीपीआर"));

        // Japanese
        p.setTitle("心肺蘇生法 (CPR)");
        p.setKeywords(List.of("心臓", "呼吸", "助けて"));
        assertTrue(p.getContent().contains("心肺蘇生法"));

        // Greek
        p.setTitle("Καρδιοπνευμονική αναζωογόνηση (ΚΑΡΠΑ)");
        p.setKeywords(List.of("καρδιά", "αναπνοή", "βοήθεια"));
        assertTrue(p.getContent().contains("ΚΑΡΠΑ"));
    }

    @Test
    @DisplayName("Should maintain field integrity")
    void fullProtocolState() {
        Protocol p = new Protocol();
        p.setId("PROT-123");
        p.setPriority(1);
        p.setCategory("MEDICAL");
        
        assertEquals("PROT-123", p.getId());
        assertEquals(1, p.getPriority());
        assertEquals("MEDICAL", p.getCategory());
    }

    @Test
    @DisplayName("Safety: getContent should handle null steps gracefully")
    void nullStepsSafety() {
        Protocol p = new Protocol();
        p.setTitle("Test");
        p.setSteps(null);
        
        String content = p.getContent();
        assertNotNull(content);
        assertTrue(content.contains("No steps defined."));
    }
}
