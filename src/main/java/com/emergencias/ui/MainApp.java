package com.emergencias.ui;

import com.emergencias.model.UserData;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 Aplicación principal JavaFX con navegación entre login y chat.
 */
public class MainApp extends Application {

    private Stage primaryStage;
    private Scene loginScene;
    private Scene chatScene;

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        
        // Cargar pantalla de login
        FXMLLoader loginLoader = new FXMLLoader(getClass().getResource("/fxml/login-view.fxml"));
        Parent loginRoot = loginLoader.load();
        loginScene = new Scene(loginRoot, 700, 800);
        loginScene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());
        
        LoginController loginController = loginLoader.getController();
        loginController.setMainApp(this);
        
        // Configurar escenario
        primaryStage.setTitle("Sistema de Gestión de Emergencias");
        primaryStage.setScene(loginScene);
        primaryStage.setMinWidth(600);
        primaryStage.setMinHeight(700);
        primaryStage.show();
        
        // Ajustar tamaño después de que la ventana se muestre para evitar que se corte
        Platform.runLater(() -> {
            primaryStage.sizeToScene();
            primaryStage.centerOnScreen();
        });
    }
    
    /**
     Cambia a la pantalla de chat después del login.
     */
    public void showChatScreen(UserData userData) throws Exception {
        FXMLLoader chatLoader = new FXMLLoader(getClass().getResource("/fxml/chat-view.fxml"));
        Parent chatRoot = chatLoader.load();
        
        ChatController chatController = chatLoader.getController();
        chatController.setUserData(userData);
        
        chatScene = new Scene(chatRoot, 800, 700);
        chatScene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());
        
        primaryStage.setScene(chatScene);
        primaryStage.setTitle("Chat de Emergencias - " + userData.getFullName());
    }
    
    /**
     Vuelve a la pantalla de login.
     */
    public void showLoginScreen() {
        primaryStage.setScene(loginScene);
        primaryStage.setTitle("Sistema de Gestión de Emergencias");
    }

    /**
     Método main para ejecutar la aplicación JavaFX
     */
    public static void main(String[] args) {
        launch(args);
    }
}