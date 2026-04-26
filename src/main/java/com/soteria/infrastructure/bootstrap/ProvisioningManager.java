package com.soteria.infrastructure.bootstrap;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import com.soteria.infrastructure.intelligence.stt.VoskSTTService;
import com.soteria.infrastructure.intelligence.triage.TriageService;
import com.soteria.infrastructure.intelligence.llm.LocalBrainService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the background execution of the provisioning process, including model downloads
 * and service initialization. This class ensures that provisioning happens sequentially
 * and can be safely interrupted.
 */
public class ProvisioningManager {
    private static final Logger log = Logger.getLogger(ProvisioningManager.class.getName());

    private String lastProvisioningKey;
    private Thread activeProvisioner;
    private CompletableFuture<?> activeDownload;

    public synchronized void start(BootstrapState state, BootstrapService service,
                                   SystemCapability.AIModelProfile profile, String language, String customUrl) {
        
        String key = profile.name() + "|" + language + "|" + (customUrl == null ? "" : customUrl);

        if (key.equals(lastProvisioningKey)) {
            CompletableFuture<Void> r = state.getReadyFuture();
            if (r.isDone() && !r.isCompletedExceptionally()) {
                return;
            }
            if (activeProvisioner != null && activeProvisioner.isAlive()) {
                return;
            }
        }

        cancelActiveTasks();

        // If we change the goal, we are no longer ready
        state.setReadyToChat(false);
        state.resetReadyFuture();

        lastProvisioningKey = key;
        activeProvisioner = new Thread(() -> runProvisioning(state, service, profile, language, customUrl), "soteria-provisioner");
        activeProvisioner.setDaemon(true);
        activeProvisioner.start();
    }

    private void runProvisioning(BootstrapState state, BootstrapService service,
                                 SystemCapability.AIModelProfile profile, String language, String customUrl) {
        try {
            log.info("Starting provisioning sequence...");
            if (isInterrupted()) return;
            log.info("Provisioning STT model...");
            provisionSTT(state, service, language);

            if (isInterrupted()) return;
            log.info("Provisioning Brain model...");
            provisionBrainModel(state, service, profile, customUrl);

            if (isInterrupted()) return;
            log.info("Provisioning Triage model...");
            provisionTriageModel(state, service);

            if (isInterrupted()) return;
            log.info("Provisioning Knowledge Base...");
            provisionKnowledgeBase(state, service);

            if (isInterrupted()) return;
            log.info("Initializing Brain service...");
            initBrainService(state, service, profile, customUrl, language);

            log.info("Provisioning complete. Setting status to Ready.");
            state.update("Ready", 1.0);
            state.completeReadyFuture();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Provisioning failed during runProvisioning", e);
            handleProvisioningError(state, e);
        } finally {
            cleanupActiveProvisioner();
        }
    }

    private void cancelActiveTasks() {
        if (activeProvisioner != null && activeProvisioner.isAlive()) {
            log.info("Cancelling previous provisioning thread...");
            activeProvisioner.interrupt();
        }
        if (activeDownload != null && !activeDownload.isDone()) {
            log.info("Cancelling active model download...");
            activeDownload.cancel(true);
        }
    }

    private boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private void provisionSTT(BootstrapState state, BootstrapService service, String language) throws IOException {
        if (!service.modelManager().isVoskModelReady(language)) {
            state.update("Downloading speech model...", 0.10);
            activeDownload = service.modelManager().downloadVoskModel(language);
            activeDownload.join();
        }

        if (isInterrupted()) return;

        state.update("Loading speech recognition...", 0.30);
        if (service.sttService() != null) {
            service.sttService().shutdown();
        }
        VoskSTTService stt = new VoskSTTService(service.modelManager().getVoskModelPath(language));
        service.setSttService(stt);
    }

    private void provisionBrainModel(BootstrapState state, BootstrapService service, 
                                     SystemCapability.AIModelProfile profile, String customUrl) {
        String modelDisplay = (customUrl != null && !customUrl.isBlank()) ? "Custom" : profile.getDisplayName();
        state.update("Downloading AI brain (" + modelDisplay + ")...", 0.40);
        activeDownload = service.modelManager().downloadBrainModel(profile, customUrl);
        activeDownload.join();
    }

    private void provisionKnowledgeBase(BootstrapState state, BootstrapService service) {
        state.update("Optimizing search engine...", 0.65);
        if (service.triageService() != null) {
            service.knowledgeBase().setEmbedder(service.triageService().getModel());
            service.triageService().setCentroid(service.knowledgeBase().getCentroid());
        }
    }

    private void provisionTriageModel(BootstrapState state, BootstrapService service) {
        state.update("Loading intent classifier...", 0.68);
        if (!service.modelManager().isTriageModelReady()) {
            activeDownload = service.modelManager().downloadTriageModel();
            activeDownload.join();
        }

        if (isInterrupted()) return;

        if (service.triageService() != null) {
            service.triageService().close();
        }
        TriageService triage = new TriageService(service.modelManager().getTriageModelPath());
        service.setTriageService(triage);
    }

    private void initBrainService(BootstrapState state, BootstrapService service, 
                                  SystemCapability.AIModelProfile profile, String customUrl, String language) {
        state.update("Loading AI brain...", 0.70);
        if (service.brainService() != null) {
            service.brainService().close();
        }
        LocalBrainService brain = new LocalBrainService(service.modelManager().getBrainModelPath(profile, customUrl), service.capability());
        service.setBrainService(brain);

        if (isInterrupted()) return;

        state.update("Warming up AI...", 0.90);
        warmUpBrain(service, language);
    }

    private void warmUpBrain(BootstrapService service, String language) {
        try {
            List<ChatMessage> primer = List.of(ChatMessage.user("SYSTEM_TEST_START_WARMUP"));
            service.brainService().generateResponse(primer, "Warmup — no real protocol needed.", language, null,
                    new com.soteria.core.port.InferenceListener() {
                        @Override public void onToken(String t) { /* Silent warmup — tokens are not used */ }
                        @Override public void onAnalysisComplete(String id, String s) { /* Silent warmup — analysis is not used */ }
                        @Override public void onComplete(String f) { /* Silent warmup completion */ }
                        @Override public void onError(Throwable e) { /* Warmup errors are non-fatal */ }
                    });
        } catch (Exception e) {
            log.log(Level.WARNING, "Warmup turn failed (non-fatal)", e);
        }
    }

    private void handleProvisioningError(BootstrapState state, Exception e) {
        if (isInterrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
            log.info("Provisioning task aborted cleanly.");
            return;
        }
        log.log(Level.SEVERE, "Provisioning failed", e);
        state.update("Setup Error: " + e.getMessage(), state.getProgress());
        state.completeReadyFutureExceptionally(e);
    }

    private synchronized void cleanupActiveProvisioner() {
        if (Thread.currentThread() == activeProvisioner) {
            activeProvisioner = null;
        }
    }

    public void shutdown() {
        if (activeProvisioner != null && activeProvisioner.isAlive()) {
            activeProvisioner.interrupt();
        }
        if (activeDownload != null && !activeDownload.isDone()) {
            activeDownload.cancel(true);
        }
    }
}
