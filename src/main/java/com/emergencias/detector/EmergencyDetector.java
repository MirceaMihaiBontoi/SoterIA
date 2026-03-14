package com.emergencias.detector;

import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import com.emergencias.services.AIClassifierClient;
import java.util.Scanner;

/**
 * Clase encargada de detectar emergencias y recopilar información inicial.
 * Soporta clasificación por IA (backend Python) con fallback a menú manual.
 */
public class EmergencyDetector {
    private static final int MIN_SEVERITY = 1;
    private static final int MAX_SEVERITY = 10;

    private final Scanner scanner;
    private final UserData userData;
    private final AIClassifierClient aiClient;

    public EmergencyDetector(UserData userData, Scanner scanner, AIClassifierClient aiClient) {
        this.scanner = scanner;
        this.userData = userData;
        this.aiClient = aiClient;
    }

    /**
     * Inicia el proceso de detección de emergencia.
     */
    public EmergencyEvent detectEmergency() {
        System.out.println("\n=== DETECCION DE EMERGENCIA ===");
        System.out.print("¿Estas en una situacion de emergencia? (S/N): ");

        if (scanner.nextLine().equalsIgnoreCase("S")) {
            String emergencyType;
            int severity;

            if (aiClient != null && aiClient.isAvailable()) {
                emergencyType = getEmergencyTypeByAI();
                if (emergencyType == null) {
                    emergencyType = getEmergencyTypeByMenu();
                    severity = getSeverityLevel();
                } else {
                    severity = getSeverityLevel();
                }
            } else {
                if (aiClient != null) {
                    System.out.println("⚠️  Backend de IA no disponible. Usando modo manual.");
                    System.out.println("    Para activar la IA, arranca el servidor Python:");
                    System.out.println("    PowerShell: cd python-backend; python -m uvicorn server:app --host 0.0.0.0 --port 8000");
                    System.out.println("    CMD:        cd python-backend && python -m uvicorn server:app --host 0.0.0.0 --port 8000");
                }
                emergencyType = getEmergencyTypeByMenu();
                severity = getSeverityLevel();
            }

            String location = getLocation();

            if (confirmEmergency(emergencyType, location, severity)) {
                return new EmergencyEvent(
                    emergencyType,
                    location,
                    severity,
                    userData.toString()
                );
            }
        }

        System.out.println("Emergencia cancelada o no confirmada.");
        return null;
    }

    private String getEmergencyTypeByAI() {
        System.out.println("\nDescribe lo que esta pasando:");
        System.out.print("> ");
        String description = scanner.nextLine().trim();

        if (description.isEmpty()) {
            System.out.println("⚠️  Descripcion vacia. Cambiando a modo manual.");
            return null;
        }

        System.out.println("Analizando con IA...");
        String jsonResponse = aiClient.classify(description);

        if (jsonResponse == null) {
            System.out.println("⚠️  Error al clasificar. Cambiando a modo manual.");
            return null;
        }

        String corrected = AIClassifierClient.extractString(jsonResponse, "corrected_text");
        String[] emergencies = AIClassifierClient.extractEmergencies(jsonResponse);

        System.out.println("\n--- Resultado de la IA ---");
        if (!corrected.equals(description.toLowerCase())) {
            System.out.println("Texto corregido: " + corrected);
        }

        String typeName = null;
        for (int i = 0; i < emergencies.length; i++) {
            String emergency = emergencies[i];
            String name = AIClassifierClient.extractString(emergency, "type_name");
            String context = AIClassifierClient.extractString(emergency, "context");
            double conf = AIClassifierClient.extractDouble(emergency, "confidence");
            String[] instructions = AIClassifierClient.extractStringArray(emergency, "instructions");

            if (i == 0) {
                typeName = name;
                System.out.println("\nSe ha detectado " + context + " (" + name + ", confianza: " + String.format("%.0f%%", conf * 100) + ")");
            } else {
                System.out.println("\nAdemas, se ha detectado " + context + " (" + name + ", confianza: " + String.format("%.0f%%", conf * 100) + ")");
            }

            if (instructions.length > 0) {
                String header = (i == 0) ? "Instrucciones" : "Instrucciones adicionales";
                System.out.println(header + " en caso de " + context + ":");
                for (String instruction : instructions) {
                    System.out.println("  - " + instruction);
                }
            }
        }

        System.out.println("\n[AVISO] Modelo de prueba con limitaciones.");
        System.out.println("El corrector ortografico tiene un diccionario limitado y puede fallar.");
        System.out.println("--------------------------");

        System.out.print("\n¿Es correcto el tipo detectado? (S/N): ");
        if (scanner.nextLine().equalsIgnoreCase("S")) {
            return typeName;
        }

        System.out.println("Seleccione manualmente:");
        return getEmergencyTypeByMenu();
    }

    private String getEmergencyTypeByMenu() {
        while (true) {
            System.out.println("\nTipos de emergencia disponibles:");
            System.out.println("1. Accidente de trafico");
            System.out.println("2. Problema medico");
            System.out.println("3. Incendio");
            System.out.println("4. Agresion");
            System.out.println("5. Desastre natural");
            System.out.println("6. Otro");

            System.out.print("Seleccione el tipo de emergencia (1-6): ");
            String input = scanner.nextLine().trim();

            switch (input) {
                case "1": return "Accidente de trafico";
                case "2": return "Problema medico";
                case "3": return "Incendio";
                case "4": return "Agresion";
                case "5": return "Desastre natural";
                case "6": return "Otro";
                default:
                    System.out.println("⚠️  Opcion no valida. Debe ingresar un numero entre 1 y 6.");
            }
        }
    }

    private String getLocation() {
        // Intentar geolocalización automática por IP
        if (aiClient != null && aiClient.isAvailable()) {
            System.out.println("\nObteniendo ubicacion automatica...");
            String geoJson = aiClient.geolocate();
            if (geoJson != null) {
                String city = AIClassifierClient.extractString(geoJson, "city");
                String region = AIClassifierClient.extractString(geoJson, "region");
                String country = AIClassifierClient.extractString(geoJson, "country");
                double lat = AIClassifierClient.extractDouble(geoJson, "lat");
                double lon = AIClassifierClient.extractDouble(geoJson, "lon");

                String autoLocation = city + ", " + region + ", " + country;
                System.out.println("Ubicacion detectada: " + autoLocation);
                System.out.printf("Coordenadas: %.4f, %.4f\n", lat, lon);
                System.out.println("[AVISO] Ubicacion aproximada por IP. Si usa VPN puede ser incorrecta.");

                System.out.print("¿Es correcta esta ubicacion? (S/N): ");
                if (scanner.nextLine().equalsIgnoreCase("S")) {
                    return autoLocation;
                }
                System.out.println("Introduzca la ubicacion manualmente:");
            } else {
                System.out.println("⚠️  No se pudo obtener la ubicacion automatica.");
            }
        }

        // Fallback: pedir ubicación manualmente
        while (true) {
            System.out.print("\nUbicacion actual de la emergencia (obligatorio): ");
            String location = scanner.nextLine().trim();
            if (!location.isEmpty()) {
                return location;
            }
            System.out.println("⚠️  Error: La ubicacion no puede estar vacia. Intente nuevamente.");
        }
    }

    private int getSeverityLevel() {
        while (true) {
            try {
                System.out.printf("\nNivel de gravedad (%d-%d): ", MIN_SEVERITY, MAX_SEVERITY);
                int severity = Integer.parseInt(scanner.nextLine());

                if (severity >= MIN_SEVERITY && severity <= MAX_SEVERITY) {
                    return severity;
                }

                System.out.printf("⚠️  Por favor, ingrese un valor entre %d y %d.\n", MIN_SEVERITY, MAX_SEVERITY);

            } catch (NumberFormatException e) {
                System.out.println("⚠️  Por favor, ingrese un numero valido.");
            }
        }
    }

    private boolean confirmEmergency(String emergencyType, String location, int severity) {
        System.out.println("\n=== RESUMEN DE LA EMERGENCIA ===");
        System.out.println("Tipo: " + emergencyType);
        System.out.println("Ubicacion: " + location);
        System.out.println("Nivel de gravedad: " + severity + "/" + MAX_SEVERITY);

        System.out.print("\n¿Confirmar envio de alerta de emergencia? (S/N): ");
        return scanner.nextLine().equalsIgnoreCase("S");
    }
}
