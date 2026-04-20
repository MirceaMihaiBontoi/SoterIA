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
    }
}
