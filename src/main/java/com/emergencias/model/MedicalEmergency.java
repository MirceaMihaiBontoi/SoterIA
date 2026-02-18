package com.emergencias.model;

/**
 * Implementación concreta para emergencias médicas.
 */
public class MedicalEmergency extends EmergencyType {

    public MedicalEmergency(String name, int priority, String description) {
        // Las emergencias médicas suelen requerir asistencia médica por defecto (true)
        super(name, priority, description, true);
    }

    @Override
    public String getResponseProtocol() {
        return "1. Evaluar signos vitales.\n" +
               "2. Estabilizar al paciente.\n" +
               "3. Preparar traslado al hospital más cercano.";
    }

    @Override
    public String[] getRequiredServices() {
        return new String[] {
            "Ambulancia Soporte Vital Avanzado",
            "Médico de urgencias",
            "Enfermero"
        };
    }
}