package com.emergencias.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AIClassifierClientTest {

    // Apunta a un puerto que nadie escucha para simular servidor caído
    private static final String DEAD_URL = "http://localhost:19999";

    @Test
    @DisplayName("isAvailable() devuelve false cuando el servidor no existe")
    void isAvailableReturnsFalseWhenServerDown() {
        AIClassifierClient client = new AIClassifierClient(DEAD_URL);
        assertFalse(client.isAvailable());
    }

    @Test
    @DisplayName("classify() devuelve null cuando el servidor no existe (sin lanzar excepción)")
    void classifyReturnsNullWhenServerDown() {
        AIClassifierClient client = new AIClassifierClient(DEAD_URL);
        assertNull(client.classify("hay un incendio"));
    }

    @Test
    @DisplayName("Circuit breaker abre tras fallos consecutivos: isAvailable() rápido sin reintentos")
    void circuitBreakerOpensAfterConsecutiveFailures() {
        AIClassifierClient client = new AIClassifierClient(DEAD_URL);

        // 3 fallos abren el circuit breaker
        client.isAvailable();
        client.isAvailable();
        client.isAvailable();

        // A partir de aquí el breaker está abierto: isAvailable() retorna false inmediatamente
        long start = System.currentTimeMillis();
        boolean available = client.isAvailable();
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(available);
        assertTrue(elapsed < 200, "Con el breaker abierto la respuesta debe ser inmediata (<200 ms), fue: " + elapsed + " ms");
    }

    @Test
    @DisplayName("classify() con breaker abierto retorna null de forma inmediata")
    void classifyFastFailsWhenCircuitOpen() {
        AIClassifierClient client = new AIClassifierClient(DEAD_URL);

        // Abrir el breaker
        client.isAvailable();
        client.isAvailable();
        client.isAvailable();

        long start = System.currentTimeMillis();
        String result = client.classify("texto de prueba");
        long elapsed = System.currentTimeMillis() - start;

        assertNull(result);
        assertTrue(elapsed < 200, "Fast fail esperado (<200 ms), fue: " + elapsed + " ms");
    }

    @Test
    @DisplayName("extractString extrae valor de clave simple")
    void extractStringWorksOnSimpleKey() {
        String json = "{\"type\":\"fire\",\"type_name\":\"Incendio\"}";
        assertEquals("fire", AIClassifierClient.extractString(json, "type"));
        assertEquals("Incendio", AIClassifierClient.extractString(json, "type_name"));
    }

    @Test
    @DisplayName("extractString devuelve null cuando la clave no existe")
    void extractStringReturnsNullForMissingKey() {
        assertNull(AIClassifierClient.extractString("{\"a\":\"b\"}", "missing"));
    }

    @Test
    @DisplayName("extractDouble extrae confidence correctamente")
    void extractDoubleExtractsConfidence() {
        String json = "{\"confidence\":0.87}";
        assertEquals(0.87, AIClassifierClient.extractDouble(json, "confidence"), 0.001);
    }

    @Test
    @DisplayName("extractEmergencies parsea el array de objetos JSON")
    void extractEmergenciesParsesArray() {
        String json = "{\"priority\":1,\"corrected_text\":\"incendio\",\"emergencies\":[" +
                      "{\"type\":\"fire\",\"confidence\":0.9}," +
                      "{\"type\":\"medical\",\"confidence\":0.1}]}";
        String[] result = AIClassifierClient.extractEmergencies(json);
        assertEquals(2, result.length);
        assertTrue(result[0].contains("\"type\":\"fire\""));
        assertTrue(result[1].contains("\"type\":\"medical\""));
    }

    @Test
    @DisplayName("extractEmergencies devuelve array vacío para JSON sin el campo")
    void extractEmergenciesHandlesMissingField() {
        String[] result = AIClassifierClient.extractEmergencies("{\"priority\":1}");
        assertEquals(0, result.length);
    }
}
