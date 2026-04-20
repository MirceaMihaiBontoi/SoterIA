package com.soteria.ui.controller;

import com.soteria.core.model.UserData;
import com.soteria.infrastructure.persistence.JsonUserPersistence;
import com.soteria.ui.MainApp;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.*;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for the login and registration screen.
 */
public class LoginController implements Initializable {

    // Login fields
    @FXML private VBox loginForm;
    @FXML private TextField loginUsernameField;
    @FXML private PasswordField loginPasswordField;
    @FXML private CheckBox rememberSessionCheck;
    @FXML private Label loginErrorLabel;
    
    // Registration fields
    @FXML private VBox registerForm;
    @FXML private TextField registerUsernameField;
    @FXML private PasswordField registerPasswordField;
    @FXML private PasswordField registerConfirmField;
    @FXML private TextField registerNameField;
    @FXML private TextField registerPhoneField;
    @FXML private TextField registerContactField;
    @FXML private TextArea registerMedicalField;
    @FXML private Label registerErrorLabel;
    
    private JsonUserPersistence userManager;
    private MainApp mainApp;
    private static final Logger log = Logger.getLogger(LoginController.class.getName());
    
    private static final String CACHE_DIR = "cache";
    private static final String SESSION_FILE = CACHE_DIR + "/session.dat";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        userManager = new JsonUserPersistence();
        showLoginForm();
    }
    
    public void setMainApp(MainApp mainApp) {
        this.mainApp = mainApp;
        Platform.runLater(this::checkSavedSession);
    }

    private void checkSavedSession() {
        File sessionFile = new File(SESSION_FILE);
        if (sessionFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(sessionFile))) {
                String username = reader.readLine();
                String password = reader.readLine();
                
                if (username != null && password != null) {
                    UserData userData = userManager.loginUser(username, password);
                    if (userData != null && mainApp != null) {
                        log.log(Level.INFO, "✅ Auto-login: {0}", username);
                        mainApp.showChatScreen(userData);
                    }
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "Error loading session", e);
            }
        }
    }

    @FXML
    private void handleLogin() {
        String username = loginUsernameField.getText().trim();
        String password = loginPasswordField.getText();
        
        if (username.isEmpty() || password.isEmpty()) {
            loginErrorLabel.setText("Please fill all fields");
            return;
        }
        
        UserData userData = userManager.loginUser(username, password);
        
        if (userData != null) {
            loginErrorLabel.setText("");
            log.log(Level.INFO, "✅ Login successful: {0}", username);
            
            if (rememberSessionCheck.isSelected()) {
                saveSession(username, password);
            }
            
            if (mainApp != null) {
                try {
                    mainApp.showChatScreen(userData);
                } catch (Exception e) {
                    loginErrorLabel.setText("Error opening chat: " + e.getMessage());
                }
            }
        } else {
            loginErrorLabel.setText("Invalid username or password");
        }
    }

    @FXML
    private void handleRegister() {
        String username = registerUsernameField.getText().trim();
        String password = registerPasswordField.getText();
        String confirm = registerConfirmField.getText();
        String name = registerNameField.getText().trim();
        String phone = registerPhoneField.getText().trim();
        String contact = registerContactField.getText().trim();
        String medical = registerMedicalField.getText().trim();
        
        if (username.isEmpty() || password.isEmpty() || name.isEmpty() || phone.isEmpty() || contact.isEmpty()) {
            registerErrorLabel.setText("Please fill all mandatory fields");
            return;
        }
        
        if (!password.equals(confirm)) {
            registerErrorLabel.setText("Passwords do not match");
            return;
        }
        
        if (password.length() < 4) {
            registerErrorLabel.setText("Password too short");
            return;
        }
        
        if (userManager.userExists(username)) {
            registerErrorLabel.setText("User already exists");
            return;
        }
        
        boolean success = userManager.registerUser(username, password, name, phone, contact, medical);
        
        if (success) {
            registerErrorLabel.setText("");
            log.log(Level.INFO, "✅ User registered: {0}", username);
            
            UserData userData = userManager.loginUser(username, password);
            if (userData != null && mainApp != null) {
                try {
                    saveSession(username, password);
                    mainApp.showChatScreen(userData);
                } catch (Exception e) {
                    registerErrorLabel.setText("Error opening chat: " + e.getMessage());
                }
            }
        } else {
            registerErrorLabel.setText("Error creating account");
        }
    }
    
    @FXML
    private void showRegisterForm() {
        loginForm.setVisible(false);
        loginForm.setManaged(false);
        registerForm.setVisible(true);
        registerForm.setManaged(true);
    }
    
    @FXML
    private void showLoginForm() {
        registerForm.setVisible(false);
        registerForm.setManaged(false);
        loginForm.setVisible(true);
        loginForm.setManaged(true);
    }
    
    private void saveSession(String username, String password) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(SESSION_FILE))) {
            writer.println(username);
            writer.println(password);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Error saving session: {0}", e.getMessage());
        }
    }
}
