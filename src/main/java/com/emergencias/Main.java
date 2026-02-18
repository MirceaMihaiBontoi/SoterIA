package com.emergencias;

import com.emergencias.alert.AlertSender;
import com.emergencias.alert.EmergencyLogger;
import com.emergencias.controller.EmergencyManager;
import com.emergencias.detector.EmergencyDetector;
import com.emergencias.model.UserData;
import com.emergencias.services.IAlert;
import java.util.Scanner;

/**
 * Clase principal que inicia la aplicación y ensambla sus componentes (Inyección de Dependencias).
 */
public class Main {
    /**
     * Punto de entrada principal de la aplicación.
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        try {
            // 1. Crear los datos del usuario
            UserData userData = new UserData(
                "Juan Pérez",
                "+34 600 123 456",
                "Alergias: Ninguna\nTipo de sangre: A+",
                "María García (Madre): +34 600 654 321"
            );
            
            // 2. Crear las dependencias (los "servicios" de la aplicación)
            EmergencyDetector detector = new EmergencyDetector(userData, scanner);
            IAlert alertSender = new AlertSender();
            EmergencyLogger logger = new EmergencyLogger();
            
            // 3. Inyectar las dependencias en el gestor principal
            EmergencyManager emergencyManager = new EmergencyManager(
                userData, 
                scanner, 
                detector, 
                alertSender, 
                logger
            );
            
            // 4. Iniciar el sistema
            emergencyManager.startSystem();
            
        } catch (Exception e) {
            System.err.println("\n=== ERROR CRÍTICO ===");
            System.err.println("Se ha producido un error inesperado: " + e.getMessage());
            e.printStackTrace();
            
        } finally {
            // Cerrar el recurso principal
            if (scanner != null) {
                scanner.close();
            }
        }
    }
}
