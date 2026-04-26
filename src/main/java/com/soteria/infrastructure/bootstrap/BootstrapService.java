package com.soteria.infrastructure.bootstrap;

import com.soteria.infrastructure.intelligence.llm.LocalBrainService;
import com.soteria.infrastructure.intelligence.rag.EmergencyKnowledgeBase;
import com.soteria.infrastructure.intelligence.system.ModelManager;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import com.soteria.infrastructure.intelligence.rag.TriageService;
import com.soteria.infrastructure.intelligence.stt.VoskSTTService;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background boot of every heavy runtime component (model downloads, Vosk,
 * Gemma, Lucene/JGraphT knowledge base) so the onboarding screen doubles as
 * a loading screen. By the time the user finishes typing their profile, the
 * chat is typically ready to respond.
 *
 * Exposes observable properties for UI progress and a CompletableFuture
 * for readiness gating. All services are held as singletons; consumers
 * must await {@link #ready()} before accessing them.
 */
public class BootstrapService {

    private static final Logger log = Logger.getLogger(BootstrapService.class.getName());

    private final BootstrapState state = new BootstrapState();
    private final ProvisioningManager provisioningManager = new ProvisioningManager();

    private static final String PROTOCOLS_PATH = System.getProperty("soteria.protocols.path", "/data/protocols/");

    private SystemCapability capability;
    private ModelManager modelManager;
    private EmergencyKnowledgeBase knowledgeBase;
    private VoskSTTService sttService;
    private TriageService triageService;
    private LocalBrainService brainService;

    /**
     * Kicks off hardware detection and local indexing. Does NOT trigger downloads.
     */
    public void preInitialize() {
        try {
            log.info("Starting pre-initialization...");
            state.update("Detecting hardware...", 0.10);
            capability = new SystemCapability();
            modelManager = new ModelManager(capability);

            state.update("Building knowledge base...", 0.30);
            knowledgeBase = new EmergencyKnowledgeBase(PROTOCOLS_PATH, modelManager.getKBIndexPath(), capability);

            state.update("System ready for setup", 1.0);
            log.info("Pre-initialization complete.");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Pre-initialization failed", e);
            state.update("Init Error: " + e.getMessage(), 0.0);
        }
    }

    /**
     * Starts model downloads and engine initialization based on user preferences.
     * This ensures that only ONE provisioning process is active.
     */
    public void startProvisioning(SystemCapability.AIModelProfile profile, String language, String customUrl) {
        log.info(() -> "BootstrapService: starting provisioning for " + profile + " in " + language);
        provisioningManager.start(state, this, profile, language, customUrl);
    }

    public CompletableFuture<Void> ready() {
        return state.getReadyFuture();
    }

    public ReadOnlyStringProperty statusProperty() {
        return state.statusProperty();
    }

    public ReadOnlyBooleanProperty readyProperty() {
        return state.readyProperty();
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return state.progressProperty();
    }

    public SystemCapability capability() {
        return capability;
    }

    public ModelManager modelManager() {
        return modelManager;
    }

    public EmergencyKnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    public VoskSTTService sttService() {
        return sttService;
    }

    public TriageService triageService() {
        return triageService;
    }

    public LocalBrainService brainService() {
        return brainService;
    }

    // --- Package-private setters for ProvisioningManager ---

    void setSttService(VoskSTTService stt) {
        this.sttService = stt;
    }

    void setTriageService(TriageService triage) {
        this.triageService = triage;
    }

    void setBrainService(LocalBrainService brain) {
        this.brainService = brain;
    }

    public void shutdown() {
        log.info("System shutdown initiated. Cleaning up resources...");

        provisioningManager.shutdown();

        // Close services safely
        closeService(sttService, "STT");
        closeService(triageService, "Triage");
        closeService(brainService, "Brain");
        closeService(knowledgeBase, "KnowledgeBase");

        log.info("Cleanup complete.");
        System.exit(0);
    }

    private void closeService(AutoCloseable service, String name) {
        try {
            if (service != null) {
                service.close();
            }
        } catch (Exception e) {
            log.log(Level.FINE, e, () -> "Cleanup of " + name + " failed (ignorable during shutdown)");
        }
    }
}
