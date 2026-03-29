package com.emergencias.ui;

import com.emergencias.model.UserData;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 Gestor de usuarios usando archivo de texto plano.
 Se usa cuando no hay base de datos disponible.
 */
public class UserFileManager {
    private static final String CACHE_DIR = "cache";
    private static final String USERS_FILE = CACHE_DIR + "/users.dat";
    private Map<String, String[]> users; // username -> [passwordHash, name, phone, contact, medical]
    
    public UserFileManager() {
        users = new HashMap<>();
        
        // Crear directorio de caché si no existe
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        loadUsers();
    }
    
    /**
     Carga usuarios desde archivo.
     */
    private void loadUsers() {
        File file = new File(USERS_FILE);
        if (!file.exists()) {
            System.out.println("📝 Archivo de usuarios no existe. Se creará al registrar primer usuario.");
            return;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                
                String[] parts = line.split("\\|");
                if (parts.length >= 6) {
                    String username = parts[0];
                    String[] userData = new String[]{parts[1], parts[2], parts[3], parts[4], parts[5]};
                    users.put(username, userData);
                }
            }
            System.out.println("✅ " + users.size() + " usuarios cargados desde archivo.");
        } catch (IOException e) {
            System.err.println("❌ Error al cargar usuarios: " + e.getMessage());
        }
    }
    
    /**
     Guarda usuarios en archivo.
     */
    private void saveUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(USERS_FILE))) {
            writer.println("# usuarios.dat - Formato: username|passwordHash|name|phone|contact|medical");
            for (Map.Entry<String, String[]> entry : users.entrySet()) {
                String username = entry.getKey();
                String[] data = entry.getValue();
                writer.println(username + "|" + data[0] + "|" + data[1] + "|" + data[2] + "|" + data[3] + "|" + data[4]);
            }
            System.out.println("✅ Usuarios guardados en archivo.");
        } catch (IOException e) {
            System.err.println("❌ Error al guardar usuarios: " + e.getMessage());
        }
    }
    
    /**
     Registra un nuevo usuario.
     */
    public boolean registerUser(String username, String password, String name, String phone, 
                                String emergencyContact, String medicalInfo) {
        if (users.containsKey(username)) {
            return false; // Usuario ya existe
        }
        
        String passwordHash = hashPassword(password);
        String[] userData = new String[]{
            passwordHash,
            name,
            phone,
            emergencyContact,
            medicalInfo.isEmpty() ? "No especificada" : medicalInfo
        };
        
        users.put(username, userData);
        saveUsers();
        return true;
    }
    
    /**
     Autentica un usuario.
     */
    public UserData loginUser(String username, String password) {
        if (!users.containsKey(username)) {
            return null;
        }
        
        String[] userData = users.get(username);
        String storedHash = userData[0];
        String inputHash = hashPassword(password);
        
        if (storedHash.equals(inputHash)) {
            return new UserData(userData[1], userData[2], userData[4], userData[3]);
        }
        
        return null;
    }
    
    /**
     Verifica si un usuario existe.
     */
    public boolean userExists(String username) {
        return users.containsKey(username);
    }
    
    /**
     Genera hash SHA-256 de la contraseña.
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("❌ Error al hashear contraseña: " + e.getMessage());
            return password;
        }
    }
}