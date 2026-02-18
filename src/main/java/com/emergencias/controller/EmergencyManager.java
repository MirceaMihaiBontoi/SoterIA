package com.emergencias.controller;

import com.emergencias.alert.AlertSender;
import com.emergencias.alert.EmergencyLogger;
import com.emergencias.detector.EmergencyDetector;
import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import com.emergencias.model.CentroSalud;
import com.emergencias.model.CentroSaludUtils;
import java.util.List;
import com.emergencias.services.IAlert;
import java.util.Scanner;

/**
 Clase controladora principal que gestiona el flujo de detección y notificación de emergencias.
 */
public class EmergencyManager {
    // Componentes del sistema
    private final EmergencyDetector detector;  // Para detectar emergencias
    private final IAlert alertSender;          // Interfaz para enviar notificaciones (Polimorfismo)
    private final UserData userData;           // Datos del usuario actual
    private final EmergencyLogger logger;      // Para registrar emergencias y feedback
    private final Scanner scanner;             // Scanner compartido de la aplicación

    /**
     Constructor que inicializa el gestor de emergencias con los datos del usuario.
     */
    public EmergencyManager(UserData userData, Scanner scanner) {
        this.userData = userData;  // Almacenar datos del usuario
        this.scanner = scanner;    // Almacenar el scanner compartido
        this.detector = new EmergencyDetector(userData, scanner);  // Inicializar detector
        this.alertSender = new AlertSender();  // Inicializar sistema de alertas (polimorfismo con IAlert)
        this.logger = new EmergencyLogger();  // Inicializar sistema de logging
    }

    /**
     * Inicia el sistema de gestión de emergencias.
     * Este método implementa el bucle principal de la aplicación
     * El sistema se ejecutará hasta que el usuario decida salir.
     */

    public void startSystem() {
        System.out.println("Sistema de Gestión de Emergencias - Iniciado");
        System.out.println("=========================================\n");
        
        try {
            // Obtener datos del usuario al iniciar
            userData.collectUserData(scanner);
            
            // Bucle principal
            while (true) {
                try {
                    // Paso 1: Detectar emergencia a través del detector
                    EmergencyEvent event = detector.detectEmergency();

                    // Si se detectó una emergencia válida
                    if (event != null) {
                        // Mostrar centros de salud de Murcia justo antes de la confirmación de éxito
                        String ubicacion = event.getLocation();
                        try {
                            // Paso 2: Registrar la emergencia en el log
                            String emergencyId = logger.logEmergency(event);
                            System.out.println("\n✅ Emergencia registrada con ID: " + emergencyId);
                            
                            // Paso 3: Enviar alerta a servicios de emergencia
                            boolean alertSent = alertSender.send(event);
                            
                            if (alertSent) {
                                // Paso 4: Notificar a los contactos de emergencia
                                alertSender.notifyContacts(userData, event);

                                // Solo si la ubicación contiene 'murcia', preguntar y mostrar los centros
                                if (ubicacion != null && ubicacion.toLowerCase().contains("murcia")) {
                                    System.out.print("\n¿Quieres ver todos los centros de salud de Murcia? (S/N): ");
                                    String verCentros = scanner.nextLine().trim();
                                    if (verCentros.equalsIgnoreCase("S")) {
                                        // CAMBIO: Usar solo el nombre del archivo, sin ruta relativa
                                        List<CentroSalud> centros = CentroSaludUtils.cargarCentros("CentrosdeSaludMurcia.json");
                                        if (centros != null) {
                                            int count = 0;
                                            System.out.println("\n=== TODOS LOS CENTROS DE SALUD DE LA REGIÓN DE MURCIA ===");
                                            for (CentroSalud centro : centros) {
                                                System.out.println("- " + centro.getNombre() + " | " + centro.getDireccion() + " | Municipio: " + centro.getMunicipio() + " | Tel: " + centro.getTelefono());
                                                count++;
                                            }
                                            if (count == 0) {
                                                System.out.println("[DEBUG] No se encontraron centros en el archivo.");
                                            }
                                            System.out.println("==================================\n");
                                        }
                                    }
                                }

                                // Confirmación al usuario
                                System.out.println("\n✅ ¡Emergencia reportada con éxito!");
                                System.out.println("Se ha creado un registro de la emergencia en el sistema.");

                                // Paso 5: Solicitar feedback del usuario
                                try {
                                    logger.collectAndLogFeedback(emergencyId, scanner);
                                    System.out.println("\n✅ Gracias por tu feedback. Nos ayuda a mejorar el sistema.");
                                } catch (Exception e) {
                                    System.err.println("⚠️  Error al recopilar feedback: " + e.getMessage());
                                }
                            } else {
                                // Manejo de error en el envío de alerta
                                System.out.println("\n❌ No se pudo enviar la alerta. Por favor, intente nuevamente o llame al 112 manualmente.");
                            }
                        } catch (Exception e) {
                            System.err.println("\n❌ Error al procesar la emergencia: " + e.getMessage());
                        }
                    }
                    
                    // Preguntar al usuario si desea realizar otra acción
                    System.out.print("\n¿Desea realizar otra acción? (S/N): ");
                    String response = scanner.nextLine().trim();
                    
                    // Salir del bucle si el usuario no desea continuar
                    if (!response.equalsIgnoreCase("S")) {
                        System.out.println("\n✅ Saliendo del sistema de emergencias. ¡Hasta pronto!");
                        break;  // Terminar el bucle principal
                    }
                    
                    // Separador visual entre operaciones
                    System.out.println("\n" + "=".repeat(80) + "\n");
                    
                } catch (Exception e) {
                    System.err.println("❌ Error en el ciclo principal: " + e.getMessage());
                    System.out.println("Intente nuevamente...\n");
                }
            }
        } catch (Exception e) {
            System.err.println("\n❌ Error crítico en el sistema: " + e.getMessage());
        }
    }
}
