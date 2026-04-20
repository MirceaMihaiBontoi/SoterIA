package com.soteria.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserDataTest {

    private static UserData sample(String name) {
        return new UserData(name, "+34 600 111 222", "Female", "1990-01-01",
                "Asma", "Luis 600333444", "BALANCED", "Spanish", null);
    }

    @Test
    @DisplayName("Record constructor assigns all components correctly")
    void parameterisedConstructorAssignsFields() {
        UserData u = sample("Ana");
        assertEquals("Ana", u.fullName());
        assertEquals("+34 600 111 222", u.phoneNumber());
        assertEquals("Female", u.gender());
        assertEquals("1990-01-01", u.birthDate());
        assertEquals("Asma", u.medicalInfo());
        assertEquals("Luis 600333444", u.emergencyContact());
        assertEquals("BALANCED", u.preferredModel());
        assertEquals("Spanish", u.preferredLanguage());
    }

    @Test
    @DisplayName("Record toString includes core components")
    void toStringIncludesKeyFields() {
        String out = sample("Ana").toString();
        assertTrue(out.contains("Ana"));
        assertTrue(out.contains("+34 600 111 222"));
        assertTrue(out.contains("Asma"));
        assertTrue(out.contains("Luis 600333444"));
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
    }
}
