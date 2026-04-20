package com.soteria.infrastructure.persistence;

import com.soteria.core.model.UserData;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User manager using a plain text file.
 * Used when no database is available.
 */
public class JsonUserPersistence {
    private static final Logger log = Logger.getLogger(JsonUserPersistence.class.getName());
    
    private static final String CACHE_DIR = "cache";
    private static final String USERS_FILE = CACHE_DIR + "/users.dat";
    private final Map<String, String[]> users; // username -> [passwordHash, name, phone, contact, medical]
    
    public JsonUserPersistence() {
        this.users = new HashMap<>();
        
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        loadUsers();
    }
    
    private void loadUsers() {
        File file = new File(USERS_FILE);
        if (!file.exists()) {
            return;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                
                String[] parts = line.split("\\|");
                if (parts.length >= 6) {
                    String username = parts[0];
                    String[] data = new String[]{parts[1], parts[2], parts[3], parts[4], parts[5]};
                    users.put(username, data);
                }
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "❌ Error loading users: {0}", e.getMessage());
        }
    }
    
    private void saveUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(USERS_FILE))) {
            writer.println("# users.dat - Format: username|passwordHash|name|phone|contact|medical");
            for (Map.Entry<String, String[]> entry : users.entrySet()) {
                String username = entry.getKey();
                String[] data = entry.getValue();
                writer.println(username + "|" + data[0] + "|" + data[1] + "|" + data[2] + "|" + data[3] + "|" + data[4]);
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "❌ Error saving users: {0}", e.getMessage());
        }
    }
    
    public boolean registerUser(String username, String password, String name, String phone, 
                                String emergencyContact, String medicalInfo) {
        if (users.containsKey(username)) {
            return false;
        }
        
        String passwordHash = hashPassword(password);
        String[] userData = new String[]{
            passwordHash,
            name,
            phone,
            emergencyContact,
            (medicalInfo == null || medicalInfo.isEmpty()) ? "Not specified" : medicalInfo
        };
        
        users.put(username, userData);
        saveUsers();
        return true;
    }
    
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
    
    public boolean userExists(String username) {
        return users.containsKey(username);
    }
    
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
            return password;
        }
    }
}
