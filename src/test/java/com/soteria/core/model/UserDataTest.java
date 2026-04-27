package com.soteria.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserDataTest {

    private static final String PHONE = "+34 600 111 222";
    private static final String MEDICAL = "Asma";
    private static final String CONTACT = "Luis 600333444";
    private static final String LANG = "Spanish";

    private static UserData sample(String name) {
        return new UserData(name, PHONE, "Female", "1990-01-01",
                MEDICAL, CONTACT, "BALANCED", LANG, null);
    }

    @Test
    @DisplayName("Record constructor assigns all components correctly")
    void parameterisedConstructorAssignsFields() {
        UserData u = sample("Ana");
        assertEquals("Ana", u.fullName());
        assertEquals(PHONE, u.phoneNumber());
        assertEquals("Female", u.gender());
        assertEquals("1990-01-01", u.birthDate());
        assertEquals(MEDICAL, u.medicalInfo());
        assertEquals(CONTACT, u.emergencyContact());
        assertEquals("BALANCED", u.preferredModel());
        assertEquals(LANG, u.preferredLanguage());
    }

    @Test
    @DisplayName("Record toString includes core components")
    void toStringIncludesKeyFields() {
        String out = sample("Ana").toString();
        assertTrue(out.contains("Ana"));
        assertTrue(out.contains(PHONE));
        assertTrue(out.contains(MEDICAL));
        assertTrue(out.contains(CONTACT));
    }

    @Test
    @DisplayName("Record equals and hashCode work correctly")
    void recordsEquality() {
        assertEquals(sample("Ana"), sample("Ana"));
        assertEquals(sample("Ana").hashCode(), sample("Ana").hashCode());
    }

    @Test
    @DisplayName("isComplete() flags draft/placeholder profiles")
    void isCompleteRejectsDrafts() {
        assertTrue(sample("Ana").isComplete());
        assertFalse(sample(UserData.INCOMPLETE_NAME).isComplete());
        assertFalse(sample(null).isComplete());
        
        // Stricter checks
        UserData missingPhone = new UserData("Ana", "", "F", "1990", "Asthma", "112", "B", "ES", null);
        assertFalse(missingPhone.isComplete(), "Should be incomplete without phone");
        
        UserData missingMedical = new UserData("Ana", "123", "F", "1990", null, "112", "B", "ES", null);
        assertFalse(missingMedical.isComplete(), "Should be incomplete without medical info");
    }

    @Test
    @DisplayName("Should support multilingual user profiles and medical data")
    void multilingualUserProfiles() {
        // Test data for names and medical conditions in various languages
        java.util.Map<String, java.util.List<String>> users = java.util.Map.of(
            "Japanese", java.util.List.of("佐藤 健", "ペニシリンアレルギー"),
            "Portuguese", java.util.List.of("João Silva", "Diabetes tipo 1"),
            "Korean", java.util.List.of("김철수", "갑상선 질환"),
            "French", java.util.List.of("Marie Dubois", "Hypertension"),
            "German", java.util.List.of("Hans Schmidt", "Herzschrittmacher")
        );

        users.forEach((lang, data) -> {
            String name = data.get(0);
            String medical = data.get(1);
            
            UserData u = new UserData(name, PHONE, "Other", "1985-05-05", 
                                    medical, "Contact 123", "HIGH_PRECISION", lang, null);
            
            assertEquals(name, u.fullName(), "Name corruption in " + lang);
            assertEquals(medical, u.medicalInfo(), "Medical info corruption in " + lang);
            assertEquals(lang, u.preferredLanguage());
            
            assertTrue(u.isComplete());
            String out = u.toString();
            assertTrue(out.contains(name), "toString missing name in " + lang);
            assertTrue(out.contains(medical), "toString missing medical info in " + lang);
        });
    }

    @Test
    @DisplayName("Should persist model configuration correctly")
    void modelConfigurationPersistence() {
        String customUrl = "http://local-llm:8080/v1";
        UserData u = new UserData("Dev", PHONE, "M", "2000-01-01", 
                                "None", "112", "CUSTOM", "English", customUrl);
        
        assertEquals("CUSTOM", u.preferredModel());
        assertEquals(customUrl, u.customModelUrl());
    }
}
