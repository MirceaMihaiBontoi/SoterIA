package com.emergencias.detector;

import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import java.util.Scanner;

/**
 * Clase encargada de detectar emergencias y recopilar información inicial.
 */
public class EmergencyDetector {
    private static final int MIN_SEVERITY = 1;
    private static final int MAX_SEVERITY = 10;
    
    private final Scanner scanner;
    private final UserData userData;

    public EmergencyDetector(UserData userData, Scanner scanner) {
        this.scanner = scanner;
        this.userData = userData;
    }

    /**
     * Inicia el proceso de detección de emergencia.
     */
    public EmergencyEvent detectEmergency() {
        System.out.println("\n=== DETECCIÓN DE EMERGENCIA ===");
        System.out.print("¿Estás en una situación de emergencia? (S/N): ");
        
        if (scanner.nextLine().equalsIgnoreCase("S")) {
            String emergencyType = getEmergencyType();
            String location = getLocation(); // Ahora la ubicación es obligatoria
            int severity = getSeverityLevel();
            
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

    private String getEmergencyType() {
        while (true) {
            System.out.println("\nTipos de emergencia disponibles:");
            System.out.println("1. Accidente de tráfico");
            System.out.println("2. Problema médico");
            System.out.println("3. Incendio");
            System.out.println("4. Agresión");
            System.out.println("5. Otro");
            
            System.out.print("Seleccione el tipo de emergencia (1-5): ");
            String input = scanner.nextLine().trim();
            
            switch (input) {
                case "1": return "Accidente de tráfico";
                case "2": return "Problema médico";
                case "3": return "Incendio";
                case "4": return "Agresión";
                case "5": return "Otro";
                default:
                    System.out.println("⚠️  Opción no válida. Debe ingresar un número entre 1 y 5.");
            }
        }
    }

    private String getLocation() {
        while (true) {
            System.out.print("\nUbicación actual de la emergencia (obligatorio): ");
            String location = scanner.nextLine().trim();
            if (!location.isEmpty()) {
                return location;
            }
            System.out.println("⚠️  Error: La ubicación no puede estar vacía. Intente nuevamente.");
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
                System.out.println("⚠️  Por favor, ingrese un número válido.");
            }
        }
    }

    private boolean confirmEmergency(String emergencyType, String location, int severity) {
        System.out.println("\n=== RESUMEN DE LA EMERGENCIA ===");
        System.out.println("Tipo: " + emergencyType);
        System.out.println("Ubicación: " + location);
        System.out.println("Nivel de gravedad: " + severity + "/" + MAX_SEVERITY);
        
        System.out.print("\n¿Confirmar envío de alerta de emergencia? (S/N): ");
        return scanner.nextLine().equalsIgnoreCase("S");
    }
}
