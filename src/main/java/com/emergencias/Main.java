package com.emergencias;

import com.emergencias.controller.EmergencyManager;
import com.emergencias.model.UserData;
import java.util.Scanner;

/**
 * Clase principal que inicia la aplicación del sistema de emergencias.
 */
public class Main {
    /**
     * Punto de entrada principal de la aplicación.
     */
    public static void main(String[] args) {
        // 0. Crear un único Scanner compartido para toda la aplicación
        Scanner scanner = new Scanner(System.in);
        
        try {
            // 1. Crear datos de ejemplo del usuario con información relevante
            UserData userData = new UserData(
                "Juan Pérez",                      // Nombre completo del usuario
                "+34 600 123 456",                // Teléfono de contacto
                "Alergias: Ninguna\nTipo de sangre: A+",  // Información médica relevante
                "María García (Madre): +34 600 654 321"  // Contacto de emergencia
            );
            
            // 2. Inicializar el gestor de emergencias con los datos del usuario y el scanner compartido
            //    El gestor se encargará de coordinar todas las operaciones del sistema
            EmergencyManager emergencyManager = new EmergencyManager(userData, scanner);
            
            // 3. Iniciar el sistema de gestión de emergencias
            //    Este método contiene el bucle principal de la aplicación
            emergencyManager.startSystem();
            
        } catch (Exception e) {
            // Captura global de excepciones inesperadas
            System.err.println("\n=== ERROR CRÍTICO ===");
            System.err.println("Se ha producido un error inesperado en la aplicación: " + e.getMessage());
            System.err.println("Por favor, contacte con soporte técnico si el problema persiste.");
            e.printStackTrace();
            
        } finally {
            // Cerrar el scanner al salir
            try {
                if (scanner != null) {
                    scanner.close();
                }
            } catch (Exception e) {
                System.err.println("Error al cerrar el scanner: " + e.getMessage());
            }
        }
    }
}
