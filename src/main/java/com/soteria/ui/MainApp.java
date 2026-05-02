package com.soteria.ui;

import atlantafx.base.theme.PrimerDark;
import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.persistence.ProfileRepository;
import com.soteria.ui.chat.ChatController;
import com.soteria.ui.onboarding.OnboardingController;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;

import com.soteria.infrastructure.intelligence.system.SystemCapability;

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

    // Mobile-preview window size (≈9:19.5, close to modern Android viewport)
    private static final double MOBILE_WIDTH = 400;
    private static final double MOBILE_HEIGHT = 860;

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        bootstrap.preInitialize();

        // Register global listener for system readiness.
        // It will trigger navigation to chat if the profile is complete.
        bootstrap.readyProperty().addListener((obs, wasReady, isReady) -> {
            if (Boolean.TRUE.equals(isReady)) {
                tryNavigateToChat();
            }
        });

        profiles.load().ifPresentOrElse(
                this::initializeSession,
                this::launchOnboardingQuietly);

        primaryStage.setTitle("SoterIA");
        primaryStage.setWidth(MOBILE_WIDTH);
        primaryStage.setHeight(MOBILE_HEIGHT);
        primaryStage.setMinWidth(360);
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
            showChatScreen(profile);

            SystemCapability.AIModelProfile profileType = parseModelProfile(profile.preferredModel());
            String lang = (profile.preferredLanguage() != null && !profile.preferredLanguage().isBlank())
                    ? profile.preferredLanguage()
                    : "English";

            bootstrap.startProvisioning(profileType, lang, profile.customModelUrl());
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
        } catch (IllegalArgumentException _) {
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

    private void showOnboarding() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/onboarding-view.fxml"));
        Parent root = loader.load();
        OnboardingController controller = loader.getController();
        controller.init(bootstrap, profiles, this);

        Scene scene = new Scene(root, MOBILE_WIDTH, MOBILE_HEIGHT);
        scene.getStylesheets().add(getClass().getResource(MAIN_CSS).toExternalForm());
        primaryStage.setScene(scene);

        // Navigation is now handled by the global readyProperty listener in start()
    }

    /**
     * Transition to chat. Called by controller once profile is fully saved.
     */
    public void completeOnboarding() {
        // Just a hint to check if we can navigate now
        tryNavigateToChat();
    }

    private synchronized void tryNavigateToChat() {
        log.info("Attempting navigation to chat...");
        if (bootstrap.readyProperty().get()) {
            profiles.load().ifPresentOrElse(
                this::navigateToChatIfRequired,
                () -> log.warning("Bootstrap is ready but NO PROFILE found. Cannot navigate yet.")
            );
        } else {
            log.info("Bootstrap is not ready yet. Navigation deferred.");
        }
    }

    private void navigateToChatIfRequired(UserData p) {
        if (!p.isComplete()) {
            log.warning("Bootstrap is ready but profile is INCOMPLETE. Cannot navigate yet.");
            return;
        }

        log.info(() -> "Profile is complete for user: " + p.fullName() + ". Swapping to chat screen.");
        Platform.runLater(() -> {
            try {
                if (isAlreadyInChat()) {
                    log.info("Already in chat screen, skipping.");
                    return;
                }
                showChatScreen(p);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to transition to chat", e);
            }
        });
    }

    private boolean isAlreadyInChat() {
        String title = primaryStage.getTitle();
        return title != null && title.startsWith("SoterIA — ");
    }

    void showChatScreen(UserData profile) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat-view.fxml"));
        Parent root = loader.load();
        ChatController controller = loader.getController();
        controller.init(profile, bootstrap, profiles);

        Scene scene = new Scene(root, MOBILE_WIDTH, MOBILE_HEIGHT);
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
