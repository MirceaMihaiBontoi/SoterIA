package com.soteria.ui;

import atlantafx.base.theme.PrimerDark;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.persistence.ProfileRepository;
import com.soteria.ui.controller.ChatController;
import com.soteria.ui.controller.OnboardingController;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.soteria.infrastructure.intelligence.SystemCapability;

/**
 * JavaFX entry point. Decides between onboarding (first run) and chat
 * (returning user) based on whether a profile exists on disk, applies
 * the AtlantaFX Primer Dark theme, and kicks off the background boot
 * as soon as the window is up.
 */
public class MainApp extends Application {

    private Stage primaryStage;
    private final BootstrapService bootstrap = new BootstrapService();
    private final ProfileRepository profiles = new ProfileRepository();
    private static final Logger log = Logger.getLogger(MainApp.class.getName());

    private static final String MAIN_CSS = "/styles/main.css";

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        bootstrap.preInitialize();

        // Register global listener for system readiness.
        // It will trigger navigation to chat if the profile is complete.
        bootstrap.readyProperty().addListener((obs, wasReady, isReady) -> {
            if (isReady) {
                tryNavigateToChat();
            }
        });

        profiles.load().ifPresentOrElse(
            this::initializeSession,
            this::launchOnboardingQuietly
        );

        primaryStage.setTitle("SoterIA");
        primaryStage.setMinWidth(640);
        primaryStage.setMinHeight(720);
        primaryStage.setOnCloseRequest(e -> bootstrap.shutdown());
        primaryStage.show();
    }

    private void initializeSession(UserData profile) {
        if (!profile.isComplete()) {
            launchOnboardingQuietly();
            return;
        }

        try {
            showInstallationProgress();
            
            SystemCapability.AIModelProfile profileType = parseModelProfile(profile.preferredModel());
            String lang = (profile.preferredLanguage() != null && !profile.preferredLanguage().isBlank())
                    ? profile.preferredLanguage()
                    : "English";

            bootstrap.startProvisioning(profileType, lang, profile.customModelUrl());
            // No need for a local listener here, the global one in start() will handle it
        } catch (Exception e) {
            log.log(Level.SEVERE, "Session initialization failed", e);
        }
    }

    private SystemCapability.AIModelProfile parseModelProfile(String modelName) {
        SystemCapability capabilities = new SystemCapability();
        if (modelName == null || modelName.isBlank()) {
            return capabilities.getRecommendedProfile();
        }
        try {
            return SystemCapability.AIModelProfile.valueOf(modelName);
        } catch (IllegalArgumentException e) {
            return capabilities.getRecommendedProfile();
        }
    }

    private void launchOnboardingQuietly() {
        try {
            showOnboarding();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to show onboarding", e);
        }
    }

    private void showOnboarding() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/onboarding-view.fxml"));
        Parent root = loader.load();
        OnboardingController controller = loader.getController();
        controller.init(bootstrap, profiles, this);

        Scene scene = new Scene(root, 720, 820);
        scene.getStylesheets().add(getClass().getResource(MAIN_CSS).toExternalForm());
        primaryStage.setScene(scene);
        
        // Navigation is now handled by the global readyProperty listener in start()
    }

    private void showInstallationProgress() throws Exception {
        // Reuse the onboarding FXML but show only the installation overlay
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/onboarding-view.fxml"));
        Parent root = loader.load();
        OnboardingController controller = loader.getController();
        controller.init(bootstrap, profiles, this);
        
        // Manually switch to installation state
        root.lookup("#step1Container").setVisible(false);
        root.lookup("#installationOverlay").setVisible(true);

        Scene scene = new Scene(root, 720, 820);
        scene.getStylesheets().add(getClass().getResource(MAIN_CSS).toExternalForm());
        primaryStage.setScene(scene);
    }

    /**
     * Transition to chat. Called by controller once profile is fully saved.
     */
    public void completeOnboarding(UserData profile) {
        // Just a hint to check if we can navigate now
        tryNavigateToChat();
    }

    private synchronized void tryNavigateToChat() {
        if (bootstrap.readyProperty().get()) {
            profiles.load().filter(UserData::isComplete).ifPresent(p -> Platform.runLater(() -> {
                try {
                    // Check if we are already in the chat screen to avoid redundant swaps
                    if (primaryStage.getTitle() != null && primaryStage.getTitle().startsWith("SoterIA — ")) {
                        return;
                    }
                    showChatScreen(p);
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Failed to transition to chat", e);
                }
            }));
        }
    }

    public void showChatScreen(UserData profile) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat-view.fxml"));
        Parent root = loader.load();
        ChatController controller = loader.getController();
        controller.init(profile, bootstrap);

        Scene scene = new Scene(root, 820, 780);
        scene.getStylesheets().add(getClass().getResource(MAIN_CSS).toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setTitle("SoterIA — " + profile.fullName());
    }

    @Override
    public void stop() {
        bootstrap.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
