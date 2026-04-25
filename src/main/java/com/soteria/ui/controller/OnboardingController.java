package com.soteria.ui.controller;

import com.soteria.core.model.UserData;
import com.soteria.infrastructure.bootstrap.BootstrapService;
import com.soteria.infrastructure.intelligence.SystemCapability;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-step setup wizard. Step 1 picks model + language and kicks off the
 * downloads in the background; step 2 collects the emergency profile while
 * those downloads run; the installation overlay shows if the user finishes
 * the form before provisioning does.
 */
public class OnboardingController {

    private static final Logger log = Logger.getLogger(OnboardingController.class.getName());
    private static final String UNKNOWN = "Unknown";
    private static final String DEFAULT_LANGUAGE = "English";
    private static final int CUSTOM_URL_MAX_LEN = 500;
    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_REQUEST_TIMEOUT = Duration.ofSeconds(8);

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

    // Cached once so auto-detection doesn't run twice.
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

    /** Cell layout: [model name] [Recommended?] ---spacer--- [x.x GB] */
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
        languageComboBox.setItems(FXCollections.observableArrayList("Spanish", DEFAULT_LANGUAGE));
        languageComboBox.setValue(DEFAULT_LANGUAGE);
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
        if (lang != null && languageComboBox.getItems().contains(lang)) {
            languageComboBox.setValue(lang);
        } else {
            languageComboBox.setValue(DEFAULT_LANGUAGE);
        }
    }

    @FXML
    private void goToStep2() {
        step1ErrorLabel.setText("");
        String url = customModelField.getText().trim();
        if (url.isEmpty()) {
            advanceToStep2();
            return;
        }
        String syntaxError = validateCustomUrlSyntax(url);
        if (syntaxError != null) {
            step1ErrorLabel.setText(syntaxError);
            return;
        }

        // Real blindaje: probe the server before letting the user move on.
        step1ErrorLabel.setText("Verifying URL…");
        continueButton.setDisable(true);
        CompletableFuture
                .supplyAsync(() -> probeCustomUrl(url))
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

    /**
     * Cheap syntactic check; reject obviously-wrong inputs before hitting the
     * network.
     */
    private String validateCustomUrlSyntax(String url) {
        if (url.length() > CUSTOM_URL_MAX_LEN) {
            return "Custom URL is too long.";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            return "Custom URL must use https://.";
        }
        if (!lower.endsWith(".gguf")) {
            return "Custom URL must point to a .gguf file.";
        }
        try {
            URI.create(url).toURL();
        } catch (IllegalArgumentException | java.net.MalformedURLException _) {
            return "Custom URL is malformed.";
        }
        return null;
    }

    /**
     * Real server reachability check: HEAD with short timeouts, redirects followed.
     * Returns null on success, or a user-facing error string. Runs on a worker
     * thread — never call from the FX thread.
     *
     * Huggingface CDNs occasionally return 405 for HEAD on the redirected
     * object; in that case we fall back to a 1-byte Range GET, which is the
     * exact request pattern the downloader uses anyway.
     */
    private String probeCustomUrl(String url) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_REQUEST_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "SoterIA/1.0 (url-probe)")
                    .header("Accept", "*/*")
                    .build();
            HttpResponse<Void> resp = client.send(head, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            if (code >= 200 && code < 300)
                return null;
            if (code == 405)
                return probeWithRange(client, url);
            return "Server responded with HTTP " + code + " — check the URL.";
        } catch (HttpTimeoutException _) {
            return "The server took too long to respond. Try again.";
        } catch (java.net.ConnectException _) {
            return "Could not reach the server. Check your internet connection.";
        } catch (java.net.UnknownHostException _) {
            return "Unknown host. Check the URL spelling.";
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "URL verification was interrupted.";
        } catch (Exception e) {
            log.log(Level.FINE, "URL probe failed", e);
            return "Could not verify URL: " + e.getMessage();
        }
    }

    private String probeWithRange(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest get = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_REQUEST_TIMEOUT)
                .header("User-Agent", "SoterIA/1.0 (url-probe)")
                .header("Range", "bytes=0-0")
                .GET()
                .build();
        HttpResponse<Void> resp = client.send(get, HttpResponse.BodyHandlers.discarding());
        int code = resp.statusCode();
        if (code == 200 || code == 206)
            return null;
        return "Server responded with HTTP " + code + " — check the URL.";
    }
}
