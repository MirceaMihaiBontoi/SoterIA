package com.emergencias.controller;

import com.emergencias.alert.EmergencyLogger;
import com.emergencias.detector.EmergencyDetector;
import com.emergencias.model.EmergencyEvent;
import com.emergencias.model.UserData;
import com.emergencias.model.CentroSalud;
import com.emergencias.model.CentroSaludUtils;
import com.emergencias.services.IAlert;
import java.util.List;
import java.util.Scanner;

/**
 * Clase controladora principal que gestiona el flujo de detección y notificación de emergencias.
 * Esta clase está desacoplada de la creación de sus dependencias.
 */
public class EmergencyManager {
    private final EmergencyDetector detector;
    private final IAlert alertSender;
    private final UserData userData;
    private final EmergencyLogger logger;
    private final Scanner scanner;

    /**
     * Constructor que recibe sus dependencias (Inyección de Dependencias).
     */
    public EmergencyManager(UserData userData, Scanner scanner, EmergencyDetector detector, IAlert alertSender, EmergencyLogger logger) {
        this.userData = userData;
        this.scanner = scanner;
        this.detector = detector;
        this.alertSender = alertSender;
        this.logger = logger;
    }

    /**
     * Inicia el sistema de gestión de emergencias.
     */
    public void startSystem() {
        System.out.println("Sistema de Gestión de Emergencias - Iniciado");
        System.out.println("=========================================\n");
        
        try {
            userData.collectUserData(scanner);
            
            while (true) {
                try {
                    EmergencyEvent event = detector.detectEmergency();

                    if (event != null) {
                        String ubicacion = event.getLocation();
                        try {
                            String emergencyId = logger.logEmergency(event);
                            System.out.println("\n✅ Emergencia registrada con ID: " + emergencyId);
                            
                            boolean alertSent = alertSender.send(event);
                            
                            if (alertSent) {
                                alertSender.notifyContacts(userData, event);

                                if (ubicacion != null && ubicacion.toLowerCase().contains("murcia")) {
                                    System.out.print("\n¿Quieres ver todos los centros de salud de Murcia? (S/N): ");
                                    String verCentros = scanner.nextLine().trim();
                                    if (verCentros.equalsIgnoreCase("S")) {
                                        // SOLUCIÓN: Usar una barra inclinada al principio para indicar la raíz del classpath.
                                        List<CentroSalud> centros = CentroSaludUtils.cargarCentros("/CentrosdeSaludMurcia.json");
                                        if (centros != null) {
                                            System.out.println("\n=== TODOS LOS CENTROS DE SALUD DE LA REGIÓN DE MURCIA ===");
                                            for (CentroSalud centro : centros) {
                                                System.out.println("- " + centro.getNombre() + " | " + centro.getDireccion() + " | Municipio: " + centro.getMunicipio() + " | Tel: " + centro.getTelefono());
                                            }
                                            System.out.println("==================================\n");
                                        }
                                    }
                                }

                                System.out.println("\n✅ ¡Emergencia reportada con éxito!");
                                System.out.println("Se ha creado un registro de la emergencia en el sistema.");

                                try {
                                    logger.collectAndLogFeedback(emergencyId, scanner);
                                    System.out.println("\n✅ Gracias por tu feedback. Nos ayuda a mejorar el sistema.");
                                } catch (Exception e) {
                                    System.err.println("⚠️  Error al recopilar feedback: " + e.getMessage());
                                }
                            } else {
                                System.out.println("\n❌ No se pudo enviar la alerta. Por favor, intente nuevamente o llame al 112 manualmente.");
                            }
                        } catch (Exception e) {
                            System.err.println("\n❌ Error al procesar la emergencia: " + e.getMessage());
                        }
                    }
                    
                    System.out.print("\n¿Desea realizar otra acción? (S/N): ");
                    String response = scanner.nextLine().trim();
                    
                    if (!response.equalsIgnoreCase("S")) {
                        System.out.println("\n✅ Saliendo del sistema de emergencias. ¡Hasta pronto!");
                        break;
                    }
                    
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
