package com.soteria.infrastructure.bootstrap;

import com.soteria.infrastructure.intelligence.ChatMessage;
import com.soteria.infrastructure.intelligence.LocalBrainService;
import com.soteria.infrastructure.intelligence.MedicalKnowledgeBase;
import com.soteria.infrastructure.intelligence.ModelManager;
import com.soteria.infrastructure.intelligence.SystemCapability;
import com.soteria.infrastructure.intelligence.VoskSTTService;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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

    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Idle");
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(0.0);
    private final ReadOnlyBooleanWrapper readyToChat = new ReadOnlyBooleanWrapper(false);
    
    // Low-level future for internal synchronization
    private final AtomicReference<CompletableFuture<Void>> ready = new AtomicReference<>(new CompletableFuture<>());

    private SystemCapability capability;
    private ModelManager modelManager;
    private MedicalKnowledgeBase knowledgeBase;
    private VoskSTTService sttService;
    private LocalBrainService brainService;

    private String lastProvisioningKey;
    private Thread activeProvisioner;
    private CompletableFuture<?> activeDownload;

    /**
     * Kicks off hardware detection and local indexing. Does NOT trigger downloads.
     */
    public void preInitialize() {
        try {
            update("Detecting hardware...", 0.10);
            capability = new SystemCapability();
            modelManager = new ModelManager(capability);

            update("Building knowledge base...", 0.30);
            knowledgeBase = new MedicalKnowledgeBase();
            
            update("System ready for setup", 1.0);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Pre-initialization failed", e);
            update("Init Error: " + e.getMessage(), 0.0);
        }
    }

    /**
     * Starts model downloads and engine initialization based on user preferences.
     * This ensures that only ONE provisioning process is active. If called with 
     * different parameters, it kills the previous attempt and resets progress.
     */
    public synchronized void startProvisioning(SystemCapability.AIModelProfile profile, String language, String customUrl) {
        String key = profile.name() + "|" + language + "|" + (customUrl == null ? "" : customUrl);
        
        if (key.equals(lastProvisioningKey)) {
            CompletableFuture<Void> r = ready.get();
            if (r.isDone() && !r.isCompletedExceptionally()) {
                return;
            }
            if (activeProvisioner != null && activeProvisioner.isAlive()) {
                return;
            }
        }
        
        // Cancel everything active
        if (activeProvisioner != null && activeProvisioner.isAlive()) {
            log.info("Cancelling previous provisioning thread...");
            activeProvisioner.interrupt();
        }
        if (activeDownload != null && !activeDownload.isDone()) {
            log.info("Cancelling active model download...");
            activeDownload.cancel(true);
        }

        // If we change the goal, we are no longer ready
        Platform.runLater(() -> readyToChat.set(false));
        if (ready.get().isDone()) {
            ready.set(new CompletableFuture<>());
        }
        
        lastProvisioningKey = key;
        activeProvisioner = new Thread(() -> runProvisioning(profile, language, customUrl), "soteria-provisioner");
        activeProvisioner.setDaemon(true);
        activeProvisioner.start();
    }

    private void runProvisioning(SystemCapability.AIModelProfile profile, String language, String customUrl) {
        try {
            if (Thread.currentThread().isInterrupted()) return;

            if (!modelManager.isVoskModelReady(language)) {
                update("Downloading speech model...", 0.10);
                activeDownload = modelManager.downloadVoskModel(language);
                activeDownload.join();
            }

            if (Thread.currentThread().isInterrupted()) return;

            update("Loading speech recognition...", 0.30);
            if (sttService != null) sttService.shutdown();
            sttService = new VoskSTTService(modelManager.getVoskModelPath(language));

            if (Thread.currentThread().isInterrupted()) return;

            String modelDisplay = (customUrl != null && !customUrl.isBlank()) ? "Custom" : profile.getDisplayName();
            update("Downloading AI brain (" + modelDisplay + ")...", 0.40);
            activeDownload = modelManager.downloadBrainModel(profile, customUrl);
            activeDownload.join();

            if (Thread.currentThread().isInterrupted()) return;

            update("Loading AI brain...", 0.70);
            if (brainService != null) brainService.close();
            brainService = new LocalBrainService(modelManager.getBrainModelPath(profile, customUrl));

            if (Thread.currentThread().isInterrupted()) return;

            update("Warming up AI...", 0.90);
            warmUpBrain(language);

            update("Ready", 1.0);
            ready.get().complete(null);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                log.info("Provisioning task aborted cleanly.");
                return;
            }
            log.log(Level.SEVERE, "Provisioning failed", e);
            update("Setup Error: " + e.getMessage(), progress.get());
            ready.get().completeExceptionally(e);
        } finally {
            synchronized (this) {
                if (Thread.currentThread() == activeProvisioner) {
                    activeProvisioner = null;
                }
            }
        }
    }

    /**
     * Runs a single silent turn so the llama.cpp KV cache holds the system
     * prompt tokens and model weights are fully resident in RAM. Cuts the
     * latency of the user's first real message.
     */
    private void warmUpBrain(String language) {
        try {
            List<ChatMessage> primer = List.of(ChatMessage.user("ping"));
            brainService.generateResponse(primer, "Warmup — no real protocol needed.", language);
        } catch (Exception e) {
            log.log(Level.WARNING, "Warmup turn failed (non-fatal)", e);
        }
    }

    private void update(String text, double pct) {
        Platform.runLater(() -> {
            status.set(text);
            progress.set(pct);
            if (pct >= 1.0 && "Ready".equals(text)) {
                readyToChat.set(true);
            }
        });
    }

    public CompletableFuture<Void> ready() {
        return ready.get();
    }

    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }
    
    public ReadOnlyBooleanProperty readyProperty() {
        return readyToChat.getReadOnlyProperty();
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public SystemCapability capability()       { return capability; }
    public ModelManager modelManager()         { return modelManager; }
    public MedicalKnowledgeBase knowledgeBase(){ return knowledgeBase; }
    public VoskSTTService sttService()         { return sttService; }
    public LocalBrainService brainService()    { return brainService; }

    public void shutdown() {
        try {
            if (sttService != null) sttService.shutdown();
        } catch (Exception ignored) {
            // Ignored during shutdown
        }
        try {
            if (brainService != null) brainService.close();
        } catch (Exception ignored) {
            // Ignored during shutdown
        }
    }
}
