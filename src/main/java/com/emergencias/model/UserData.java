package com.emergencias.model;

import java.util.Scanner;

/**
 * Clase que contiene los datos personales del usuario para situaciones de emergencia.
 */
public class UserData {
    private String fullName;
    private String phoneNumber;
    private String medicalInfo;
    private String emergencyContact;

    /**
     * Constructor para crear un nuevo objeto UserData.
     */
    public UserData(String fullName, String phoneNumber, String medicalInfo, String emergencyContact) {
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.medicalInfo = medicalInfo;
        this.emergencyContact = emergencyContact;
    }

    // Getters y Setters
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getMedicalInfo() { return medicalInfo; }
    public void setMedicalInfo(String medicalInfo) { this.medicalInfo = medicalInfo; }
    public String getEmergencyContact() { return emergencyContact; }
    public void setEmergencyContact(String emergencyContact) { this.emergencyContact = emergencyContact; }

    /**
     * Recopila y valida los datos del usuario mediante entrada por consola.
     */
    public void collectUserData(Scanner scanner) {
        System.out.println("\n=== REGISTRO DE DATOS DE USUARIO ===");
        
        // Obtener nombre completo
        while (true) {
            System.out.print("Ingrese su nombre completo: ");
            String inputName = scanner.nextLine().trim();
            if (!inputName.isEmpty()) {
                this.fullName = inputName;
                break;
            }
            System.out.println("⚠️  Error: El nombre no puede estar vacío. Intente nuevamente.");
        }
        
        // Obtener y validar número de teléfono
        while (true) {
            System.out.print("Ingrese su número de teléfono (mínimo 9 dígitos): ");
            String inputPhone = scanner.nextLine().trim();
            // Elimina espacios, guiones o prefijos para contar solo los dígitos
            String digitsOnly = inputPhone.replaceAll("[\\s-]", "");
            if (digitsOnly.matches("\\d{9,}")) { // Regex para 9 o más dígitos
                this.phoneNumber = inputPhone;
                break;
            }
            System.out.println("⚠️  Error: El teléfono debe contener al menos 9 dígitos. Intente nuevamente.");
        }
        
        // Obtener información médica (opcional)
        System.out.print("Ingrese información médica relevante (alergias, etc.) [opcional]: ");
        this.medicalInfo = scanner.nextLine().trim();
        if (this.medicalInfo.isEmpty()) {
            this.medicalInfo = "No especificada";
        }
        
        // Obtener contacto de emergencia
        while (true) {
            System.out.print("Ingrese nombre y teléfono de contacto de emergencia: ");
            String inputContact = scanner.nextLine().trim();
            if (!inputContact.isEmpty()) {
                this.emergencyContact = inputContact;
                break;
            }
            System.out.println("⚠️  Error: El contacto de emergencia no puede estar vacío.");
        }
        
        System.out.println("\n✅ ¡Gracias! Sus datos han sido registrados correctamente.");
        System.out.println("==========================================\n");
    }

    @Override
    public String toString() {
        return String.format(
            "Nombre: %s\nTeléfono: %s\nContacto de emergencia: %s\nInformación médica: %s",
            fullName, phoneNumber, emergencyContact, medicalInfo
        );
    }
}
