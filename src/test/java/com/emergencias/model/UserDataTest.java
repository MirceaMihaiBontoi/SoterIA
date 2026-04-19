package com.emergencias.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserDataTest {

    @Test
    @DisplayName("Constructor vacío inicializa cadenas vacías, no null")
    void emptyConstructorInitialisesBlanks() {
        UserData u = new UserData();
        assertEquals("", u.getFullName());
        assertEquals("", u.getPhoneNumber());
        assertEquals("", u.getMedicalInfo());
        assertEquals("", u.getEmergencyContact());
    }

    @Test
    @DisplayName("Constructor con datos asigna todos los campos")
    void parameterisedConstructorAssignsFields() {
        UserData u = new UserData("Ana", "600111222", "Asma", "Luis 600333444");
        assertEquals("Ana", u.getFullName());
        assertEquals("600111222", u.getPhoneNumber());
        assertEquals("Asma", u.getMedicalInfo());
        assertEquals("Luis 600333444", u.getEmergencyContact());
    }

    @Test
    @DisplayName("toString incluye los campos clave en una sola cadena")
    void toStringIncludesKeyFields() {
        UserData u = new UserData("Ana", "600111222", "Asma", "Luis 600333444");
        String out = u.toString();
        assertTrue(out.contains("Ana"));
        assertTrue(out.contains("600111222"));
        assertTrue(out.contains("Asma"));
        assertTrue(out.contains("Luis 600333444"));
    }
}
