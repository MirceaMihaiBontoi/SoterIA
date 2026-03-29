package com.emergencias.ui;

import com.emergencias.model.UserData;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.*;
import java.net.URL;
import java.util.ResourceBundle;

/**
 Controlador para la pantalla de login y registro.
 */
public class LoginController implements Initializable {

    // Campos de login
    @FXML private VBox loginForm;
    @FXML private TextField loginUsernameField;
    @FXML private PasswordField loginPasswordField;
    @FXML private CheckBox rememberSessionCheck;
    @FXML private Label loginErrorLabel;
    
    // Campos de registro
    @FXML private VBox registerForm;
    @FXML private TextField registerUsernameField;
    @FXML private PasswordField registerPasswordField;
    @FXML private PasswordField registerConfirmField;
    @FXML private TextField registerNameField;
    @FXML private TextField registerPhoneField;
    @FXML private TextField registerContactField;
    @FXML private TextArea registerMedicalField;
    @FXML private Label registerErrorLabel;
    
    private UserFileManager userManager;
    private MainApp mainApp;
    private static final String CACHE_DIR = "cache";
    private static final String SESSION_FILE = CACHE_DIR + "/session.dat";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Crear directorio de caché si no existe
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        userManager = new UserFileManager();
        
        // Forzar recálculo del layout
        showLoginForm();
    }
    
    public void setMainApp(MainApp mainApp) {
        this.mainApp = mainApp;
        
        // Verificar si hay sesión guardada DESPUÉS de que mainApp esté disponible
        // Usar Platform.runLater para asegurar que la ventana se muestre primero
        Platform.runLater(() -> checkSavedSession());
    }

    /**
     Verifica si hay una sesión guardada e intenta auto-login.
     */
    private void checkSavedSession() {
        File sessionFile = new File(SESSION_FILE);
        if (sessionFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(sessionFile))) {
                String username = reader.readLine();
                String password = reader.readLine();
                
                if (username != null && password != null) {
                    UserData userData = userManager.loginUser(username, password);
                    if (userData != null && mainApp != null) {
                        System.out.println("✅ Auto-login con sesión guardada: " + username);
                        mainApp.showChatScreen(userData);
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error al cargar sesión: " + e.getMessage());
            }
        }
    }

    @FXML
    private void handleLogin() {
        String username = loginUsernameField.getText().trim();
        String password = loginPasswordField.getText();
        
        if (username.isEmpty() || password.isEmpty()) {
            loginErrorLabel.setText("Por favor completa todos los campos");
            return;
        }
        
        UserData userData = userManager.loginUser(username, password);
        
        if (userData != null) {
            loginErrorLabel.setText("");
            System.out.println("✅ Login exitoso: " + username);
            
            // Guardar sesión si está marcado el checkbox
            if (rememberSessionCheck.isSelected()) {
                saveSession(username, password);
            }
            
            if (mainApp != null) {
                try {
                    mainApp.showChatScreen(userData);
                } catch (Exception e) {
                    loginErrorLabel.setText("Error al abrir chat: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } else {
            loginErrorLabel.setText("Usuario o contraseña incorrectos");
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
        
        // Validaciones
        if (username.isEmpty() || password.isEmpty() || name.isEmpty() || phone.isEmpty() || contact.isEmpty()) {
            registerErrorLabel.setText("Por favor completa los campos obligatorios");
            return;
        }
        
        if (!password.equals(confirm)) {
            registerErrorLabel.setText("Las contraseñas no coinciden");
            return;
        }
        
        if (password.length() < 4) {
            registerErrorLabel.setText("La contraseña debe tener al menos 4 caracteres");
            return;
        }
        
        if (userManager.userExists(username)) {
            registerErrorLabel.setText("El usuario ya existe");
            return;
        }
        
        // Registrar usuario
        boolean success = userManager.registerUser(username, password, name, phone, contact, medical);
        
        if (success) {
            registerErrorLabel.setText("");
            System.out.println("✅ Usuario registrado: " + username);
            
            // Auto-login después del registro
            UserData userData = userManager.loginUser(username, password);
            if (userData != null && mainApp != null) {
                try {
                    // Guardar sesión automáticamente después del registro
                    saveSession(username, password);
                    mainApp.showChatScreen(userData);
                } catch (Exception e) {
                    registerErrorLabel.setText("Error al abrir chat: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } else {
            registerErrorLabel.setText("Error al crear la cuenta");
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
    
    /**
     Guarda la sesión en archivo.
     */
    private void saveSession(String username, String password) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(SESSION_FILE))) {
            writer.println(username);
            writer.println(password);
            System.out.println("✅ Sesión guardada para: " + username);
        } catch (IOException e) {
            System.err.println("Error al guardar sesión: " + e.getMessage());
        }
    }
}