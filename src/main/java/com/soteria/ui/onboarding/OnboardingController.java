package com.soteria.ui.onboarding;

import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import com.soteria.infrastructure.persistence.ProfileRepository;
import com.soteria.infrastructure.sensor.DevicePhoneDetector;
import com.soteria.infrastructure.sensor.SystemGPSLocation;
import com.soteria.ui.MainApp;
import com.soteria.ui.i18n.UiLocales;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-step setup wizard for first launch.
 *
 * <p>Step 1 chooses the on-device Gemma 4 model profile and UI language. Step 2 collects the emergency profile while
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

    @FXML
    private Label onboardingTitleLabel;
    @FXML
    private Label step1SubtitleLabel;
    @FXML
    private Label modelFieldLabel;
    @FXML
    private Label languageFieldLabel;
    @FXML
    private Label step2SubtitleLabel;
    @FXML
    private Label fullNameFieldLabel;
    @FXML
    private Label genderFieldLabel;
    @FXML
    private Label birthDateFieldLabel;
    @FXML
    private Label contactFieldLabel;
    @FXML
    private Label medicalFieldLabel;
    @FXML
    private Button backButton;
    @FXML
    private Button finishButton;
    @FXML
    private Label installTitleLabel;
    @FXML
    private Label installBodyLabel;
    @FXML
    private Label installHoldLabel;

    // Step 1
    @FXML
    private ComboBox<SystemCapability.AIModelProfile> modelComboBox;
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
    private ComboBox<GenderOption> genderComboBox;
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

    /** Set when geolocation returns; drives localized location line. */
    private String lastLocationDescription;

    /** Cached once so auto-detection does not run twice. */
    private String devicePhone = DevicePhoneDetector.UNKNOWN;

    private enum GenderOption {
        MALE("Male", "onboarding.gender.male"),
        FEMALE("Female", "onboarding.gender.female"),
        OTHER("Other", "onboarding.gender.other"),
        PREFER_NOT("Prefer not to say", "onboarding.gender.prefer_not");

        private final String persisted;
        private final String messageKey;

        GenderOption(String persisted, String messageKey) {
            this.persisted = persisted;
            this.messageKey = messageKey;
        }
    }

    public void init(BootstrapService bootstrap, ProfileRepository profiles, MainApp mainApp) {
        this.bootstrap = bootstrap;
        this.profiles = profiles;
        this.mainApp = mainApp;

        setupModelComboBox();
        setupLanguageAndGender();
        syncLocaleFromLanguageCombo();
        applyOnboardingFormI18n();
        languageComboBox.valueProperty().addListener((obs, previous, selected) -> {
            if (selected == null) {
                return;
            }
            syncLocaleFromLanguageCombo();
            applyOnboardingFormI18n();
        });

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
        configureModelComboFactories();
    }

    private void configureModelComboFactories() {
        Callback<ListView<SystemCapability.AIModelProfile>, ListCell<SystemCapability.AIModelProfile>> cellFactory =
                lv -> new ModelCell();
        modelComboBox.setCellFactory(cellFactory);
        modelComboBox.setButtonCell(cellFactory.call(null));
    }

    /**
     * Cell layout: [model name] [Recommended?] --- spacer --- [x.x GB]
     */
    private final class ModelCell extends ListCell<SystemCapability.AIModelProfile> {
        private final Label name = new Label();
        private final Label recommended = new Label();
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
            name.setText(loc().getMessage(item.getMessageKey()));
            size.setText(String.format(Locale.ROOT, "%.1f GB", item.getSizeGB()));
            recommended.setText(loc().getMessage("onboarding.model.recommended"));
            recommended.setVisible(item == bootstrap.capability().getRecommendedProfile());
            recommended.setManaged(recommended.isVisible());
            setText(null);
            setGraphic(row);
        }
    }

    private void setupLanguageAndGender() {
        languageComboBox.setItems(FXCollections.observableArrayList(OnboardingLanguageCatalog.SUPPORTED));
        languageComboBox.setValue(OnboardingLanguageCatalog.DEFAULT);
        genderComboBox.setItems(FXCollections.observableArrayList(GenderOption.values()));
        configureGenderComboFactories();
        genderComboBox.getSelectionModel().selectFirst();
    }

    private void configureGenderComboFactories() {
        Callback<ListView<GenderOption>, ListCell<GenderOption>> genderCellFactory =
                lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(GenderOption item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : loc().getMessage(item.messageKey));
                    }
                };
        genderComboBox.setCellFactory(genderCellFactory);
        genderComboBox.setButtonCell(genderCellFactory.call(null));
    }

    private com.soteria.core.port.LocalizationService loc() {
        return bootstrap.localizationService();
    }

    private void syncLocaleFromLanguageCombo() {
        String lang = languageComboBox.getValue();
        if (lang == null) {
            return;
        }
        bootstrap.localizationService().setLocale(UiLocales.fromPreferredLanguage(lang));
    }

    private void applyOnboardingFormI18n() {
        var localization = loc();
        onboardingTitleLabel.setText(localization.getMessage("onboarding.title"));
        step1SubtitleLabel.setText(localization.getMessage("onboarding.step1"));
        modelFieldLabel.setText(localization.getMessage("onboarding.model.label"));
        languageFieldLabel.setText(localization.getMessage("onboarding.language"));
        continueButton.setText(localization.getMessage("onboarding.continue"));
        step2SubtitleLabel.setText(localization.getMessage("onboarding.step2"));
        fullNameFieldLabel.setText(localization.getMessage("onboarding.field.full_name"));
        nameField.setPromptText(localization.getMessage("onboarding.field.name_prompt"));
        genderFieldLabel.setText(localization.getMessage("onboarding.field.gender"));
        birthDateFieldLabel.setText(localization.getMessage("onboarding.field.birth_date"));
        contactFieldLabel.setText(localization.getMessage("onboarding.field.emergency_contact"));
        contactField.setPromptText(localization.getMessage("onboarding.field.contact_prompt"));
        medicalFieldLabel.setText(localization.getMessage("onboarding.field.medical"));
        medicalField.setPromptText(localization.getMessage("onboarding.field.medical_prompt"));
        backButton.setText(localization.getMessage("onboarding.action.back"));
        finishButton.setText(localization.getMessage("onboarding.action.finish"));
        installTitleLabel.setText(localization.getMessage("onboarding.install.title"));
        installBodyLabel.setText(localization.getMessage("onboarding.install.body"));
        installHoldLabel.setText(localization.getMessage("onboarding.install.hold"));
        if (lastLocationDescription != null && !lastLocationDescription.isBlank()) {
            locationLabel.setText(localization.formatMessage("onboarding.location.format", lastLocationDescription));
        } else {
            locationLabel.setText(localization.getMessage("onboarding.location.detecting"));
        }
        configureGenderComboFactories();
        configureModelComboFactories();
    }

    private void restoreDraftIfExists() {
        profiles.load().ifPresent(profile -> {
            if (profile.isComplete()) {
                return;
            }
            applyDraftFromProfile(profile);
        });
    }

    private void applyDraftFromProfile(UserData profile) {
        log.info("Restoring draft profile from previous session...");
        if (profile.preferredModel() != null) {
            SystemCapability.AIModelProfile p = SystemCapability.parseStoredProfile(profile.preferredModel());
            if (p != null) {
                modelComboBox.setValue(p);
            }
        }
        if (profile.preferredLanguage() != null) {
            selectLanguageSafely(profile.preferredLanguage());
        }
        if (profile.gender() != null) {
            selectGenderSafely(profile.gender());
        }
        Platform.runLater(this::advanceToStep2);
    }

    private void detectLocation() {
        final SystemGPSLocation detector = new SystemGPSLocation();
        Thread t = new Thread(() -> {
            String lang = detector.detectPrimaryLanguage();
            String desc = detector.getLocationDescription();
            Platform.runLater(() -> {
                lastLocationDescription = desc;
                selectLanguageSafely(lang);
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

    private void selectGenderSafely(String persistedGender) {
        if (persistedGender == null || persistedGender.isBlank()) {
            genderComboBox.getSelectionModel().selectFirst();
            return;
        }
        for (GenderOption g : GenderOption.values()) {
            if (g.persisted.equals(persistedGender)) {
                genderComboBox.setValue(g);
                return;
            }
        }
        genderComboBox.getSelectionModel().selectFirst();
    }

    @FXML
    private void goToStep2() {
        step1ErrorLabel.setText("");
        advanceToStep2();
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

        try {
            UserData draft = new UserData(
                    UserData.INCOMPLETE_NAME, devicePhone, GenderOption.OTHER.persisted, UNKNOWN,
                    "", "Pending Setup",
                    selectedProfile.name(),
                    selectedLang,
                    null,
                    null);
            profiles.save(draft);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to save draft profile", e);
        }

        log.info(() -> "Triggering provisioning for profile: " + selectedProfile + ", lang: " + selectedLang);
        bootstrap.startProvisioning(selectedProfile, selectedLang);
    }

    @FXML
    private void handleStart() {
        String name = nameField.getText().trim();
        String contact = contactField.getText().trim();

        if (name.isEmpty() || contact.isEmpty()) {
            errorLabel.setText(loc().getMessage("onboarding.error.name_contact"));
            return;
        }
        errorLabel.setText("");

        try {
            triggerProvisioning(); // idempotent — same key returns early

            SystemCapability.AIModelProfile selectedProfile = modelComboBox.getValue();
            String selectedLang = languageComboBox.getValue();

            UserData profile = new UserData(
                    name,
                    devicePhone,
                    genderComboBox.getValue().persisted,
                    birthDatePicker.getValue() != null
                            ? birthDatePicker.getValue().format(DateTimeFormatter.ISO_LOCAL_DATE)
                            : UNKNOWN,
                    medicalField.getText(),
                    contact,
                    selectedProfile.name(),
                    selectedLang,
                    null,
                    null);
            log.info("User clicked Finish. Saving complete profile...");
            profiles.save(profile);

            log.info("Transitioning to installation overlay...");
            step2Container.setVisible(false);
            installationOverlay.setVisible(true);

            log.info("Notifying MainApp to complete onboarding...");
            mainApp.completeOnboarding();

        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to finish onboarding", e);
            errorLabel.setText(loc().formatMessage("onboarding.error.save", e.getMessage()));
        }
    }
}
