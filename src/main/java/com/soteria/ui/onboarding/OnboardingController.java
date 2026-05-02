package com.soteria.ui.onboarding;

import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import com.soteria.infrastructure.persistence.ProfileRepository;
import com.soteria.infrastructure.sensor.DevicePhoneDetector;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.MainApp;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-step setup wizard for first launch.
 *
 * <p>Step 1 chooses the on-device model profile, UI language, and optional custom GGUF URL (verified
 * asynchronously via {@link OnboardingCustomUrlVerifier}). Step 2 collects the emergency profile while
 * {@link BootstrapService#startProvisioning} runs. If the user finishes before downloads complete, the
 * installation overlay reflects {@link BootstrapService} progress.</p>
 */
public class OnboardingController {

    private static final Logger log = Logger.getLogger(OnboardingController.class.getName());
    private static final String UNKNOWN = "Unknown";

    @FXML
    private VBox step1Container;
    @FXML
    private VBox step2Container;
    @FXML
    private VBox installationOverlay;

    // Step 1
    @FXML
    private ComboBox<SystemCapability.AIModelProfile> modelComboBox;
    @FXML
    private TextField customModelField;
    @FXML
    private ComboBox<String> languageComboBox;
    @FXML
    private Label locationLabel;
    @FXML
    private Label step1ErrorLabel;
    @FXML
    private Button continueButton;

    // Step 2
    @FXML
    private TextField nameField;
    @FXML
    private ComboBox<String> genderComboBox;
    @FXML
    private DatePicker birthDatePicker;
    @FXML
    private TextField contactField;
    @FXML
    private TextArea medicalField;
    @FXML
    private Label errorLabel;

    // Setup feedback
    @FXML
    private Label bootStatusLabel;
    @FXML
    private ProgressBar bootProgress;

    private BootstrapService bootstrap;
    private ProfileRepository profiles;
    private MainApp mainApp;

    /** Cached once so auto-detection does not run twice. */
    private String devicePhone = DevicePhoneDetector.UNKNOWN;

    public void init(BootstrapService bootstrap, ProfileRepository profiles, MainApp mainApp) {
        this.bootstrap = bootstrap;
        this.profiles = profiles;
        this.mainApp = mainApp;

        setupModelComboBox();
        setupLanguageAndGender();
        detectLocation();
        detectDevicePhone();

        bootStatusLabel.textProperty().bind(bootstrap.statusProperty());
        bootProgress.progressProperty().bind(bootstrap.progressProperty());

        // Non-visible steps don't reserve layout space.
        step1Container.managedProperty().bind(step1Container.visibleProperty());
        step2Container.managedProperty().bind(step2Container.visibleProperty());
        installationOverlay.managedProperty().bind(installationOverlay.visibleProperty());

        restoreDraftIfExists();
    }

    private void setupModelComboBox() {
        modelComboBox.setItems(FXCollections.observableArrayList(SystemCapability.AIModelProfile.values()));
        modelComboBox.setValue(bootstrap.capability().getRecommendedProfile());

        Callback<ListView<SystemCapability.AIModelProfile>, ListCell<SystemCapability.AIModelProfile>> cellFactory = lv -> new ModelCell();
        modelComboBox.setCellFactory(cellFactory);
        modelComboBox.setButtonCell(cellFactory.call(null));
    }

    /**
     * Cell layout: [model name] [Recommended?] --- spacer --- [x.x GB]
     */
    private final class ModelCell extends ListCell<SystemCapability.AIModelProfile> {
        private final Label name = new Label();
        private final Label recommended = new Label("Recommended");
        private final Label size = new Label();
        private final HBox row;

        ModelCell() {
            recommended.getStyleClass().add("recommended-tag");
            size.getStyleClass().add("size-tag");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row = new HBox(8, name, recommended, spacer, size);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(SystemCapability.AIModelProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            name.setText(item.getDisplayName());
            size.setText(String.format(Locale.ROOT, "%.1f GB", item.getSizeGB()));
            recommended.setVisible(item == bootstrap.capability().getRecommendedProfile());
            recommended.setManaged(recommended.isVisible());
            setText(null);
            setGraphic(row);
        }
    }

    private void setupLanguageAndGender() {
        languageComboBox.setItems(FXCollections.observableArrayList(OnboardingLanguageCatalog.SUPPORTED));
        languageComboBox.setValue(OnboardingLanguageCatalog.DEFAULT);
        genderComboBox.setItems(FXCollections.observableArrayList("Male", "Female", "Other", "Prefer not to say"));
        genderComboBox.getSelectionModel().selectFirst();
    }

    private void restoreDraftIfExists() {
        profiles.load().ifPresent(profile -> {
            if (!profile.isComplete()) {
                log.info("Restoring draft profile from previous session...");
                if (profile.preferredModel() != null) {
                    try {
                        modelComboBox.setValue(SystemCapability.AIModelProfile.valueOf(profile.preferredModel()));
                    } catch (IllegalArgumentException _) {
                        /* fall back to recommended */ }
                }
                if (profile.preferredLanguage() != null) {
                    selectLanguageSafely(profile.preferredLanguage());
                }
                if (profile.customModelUrl() != null) {
                    customModelField.setText(profile.customModelUrl());
                }
                Platform.runLater(this::advanceToStep2);
            }
        });
    }

    private void detectLocation() {
        final SystemGPSLocation detector = new SystemGPSLocation();
        Thread t = new Thread(() -> {
            String lang = detector.detectPrimaryLanguage();
            String desc = detector.getLocationDescription();
            Platform.runLater(() -> {
                selectLanguageSafely(lang);
                locationLabel.setText("Location: " + desc);
            });
        }, "soteria-geo-detect");
        t.setDaemon(true);
        t.start();
    }

    private void detectDevicePhone() {
        Thread t = new Thread(() -> {
            String detected = DevicePhoneDetector.detect();
            if (detected != null && !detected.isBlank()) {
                this.devicePhone = detected;
            }
        }, "soteria-phone-detect");
        t.setDaemon(true);
        t.start();
    }

    private void selectLanguageSafely(String lang) {
        languageComboBox.setValue(OnboardingLanguageCatalog.matchOrDefault(lang));
    }

    @FXML
    private void goToStep2() {
        step1ErrorLabel.setText("");
        String url = customModelField.getText().trim();
        if (url.isEmpty()) {
            advanceToStep2();
            return;
        }
        String syntaxError = OnboardingCustomUrlVerifier.validateSyntax(url);
        if (syntaxError != null) {
            step1ErrorLabel.setText(syntaxError);
            return;
        }

        step1ErrorLabel.setText("Verifying URL…");
        continueButton.setDisable(true);
        CompletableFuture
                .supplyAsync(() -> OnboardingCustomUrlVerifier.probe(url))
                .whenComplete((err, ex) -> Platform.runLater(() -> {
                    continueButton.setDisable(false);
                    String finalMsg = (ex != null) ? ("Could not verify URL: " + ex.getMessage()) : err;
                    if (finalMsg != null) {
                        step1ErrorLabel.setText(finalMsg);
                    } else {
                        step1ErrorLabel.setText("");
                        advanceToStep2();
                    }
                }));
    }

    private void advanceToStep2() {
        step1Container.setVisible(false);
        step2Container.setVisible(true);
        triggerProvisioning();
    }

    @FXML
    private void goToStep1() {
        step2Container.setVisible(false);
        step1Container.setVisible(true);
    }

    private void triggerProvisioning() {
        SystemCapability.AIModelProfile selectedProfile = modelComboBox.getValue();
        String selectedLang = languageComboBox.getValue();
        String customUrl = customModelField.getText().trim();
        String finalUrl = customUrl.isEmpty() ? null : customUrl;

        try {
            UserData draft = new UserData(
                    UserData.INCOMPLETE_NAME, devicePhone, "Other", UNKNOWN,
                    "", "Pending Setup",
                    selectedProfile.name(),
                    selectedLang,
                    finalUrl);
            profiles.save(draft);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to save draft profile", e);
        }

        log.info(() -> "Triggering provisioning for profile: " + selectedProfile + ", lang: " + selectedLang);
        bootstrap.startProvisioning(selectedProfile, selectedLang, finalUrl);
    }

    @FXML
    private void handleStart() {
        String name = nameField.getText().trim();
        String contact = contactField.getText().trim();

        if (name.isEmpty() || contact.isEmpty()) {
            errorLabel.setText("Name and Emergency Contact are required.");
            return;
        }
        errorLabel.setText("");

        try {
            triggerProvisioning(); // idempotent — same key returns early

            String customUrl = customModelField.getText().trim();
            boolean isCustom = !customUrl.isEmpty();
            SystemCapability.AIModelProfile selectedProfile = modelComboBox.getValue();
            String selectedLang = languageComboBox.getValue();

            UserData profile = new UserData(
                    name,
                    devicePhone,
                    genderComboBox.getValue(),
                    birthDatePicker.getValue() != null
                            ? birthDatePicker.getValue().format(DateTimeFormatter.ISO_LOCAL_DATE)
                            : UNKNOWN,
                    medicalField.getText(),
                    contact,
                    selectedProfile.name(),
                    selectedLang,
                    isCustom ? customUrl : null);
            log.info("User clicked Finish. Saving complete profile...");
            profiles.save(profile);

            log.info("Transitioning to installation overlay...");
            step2Container.setVisible(false);
            installationOverlay.setVisible(true);

            log.info("Notifying MainApp to complete onboarding...");
            mainApp.completeOnboarding();

        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to finish onboarding", e);
            errorLabel.setText("Error saving profile: " + e.getMessage());
        }
    }
}
