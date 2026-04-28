package com.soteria.infrastructure.bootstrap;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.port.InferenceListener;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import com.soteria.infrastructure.intelligence.stt.VoskSTTService;
import com.soteria.infrastructure.intelligence.tts.SherpaTTSService;
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
            log.info("Provisioning TTS model...");
            provisionTTSModel(state, service, language);

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
            state.update("Downloading speech model (" + language + ")...", 0.10);
            activeDownload = service.modelManager().downloadVoskModel(language);
            activeDownload.join();
        } else {
            state.update("Speech model ready", 0.20);
        }

        if (isInterrupted()) return;

        state.update("Loading speech recognition...", 0.30);
        if (service.sttServiceImpl() != null) {
            service.sttServiceImpl().shutdown();
        }
        VoskSTTService stt = new VoskSTTService(service.modelManager().getVoskModelPath(language));
        service.setSttService(stt);
    }

    private void provisionBrainModel(BootstrapState state, BootstrapService service, 
                                     SystemCapability.AIModelProfile profile, String customUrl) {
        if (!service.modelManager().isBrainModelReady(profile, customUrl)) {
            String modelDisplay = (customUrl != null && !customUrl.isBlank()) ? "Custom" : profile.getDisplayName();
            state.update("Downloading AI brain (" + modelDisplay + ")...", 0.40);
            activeDownload = service.modelManager().downloadBrainModel(profile, customUrl);
            activeDownload.join();
        } else {
            state.update("AI brain model ready", 0.60);
        }
    }

    private void provisionKnowledgeBase(BootstrapState state, BootstrapService service) {
        state.update("Optimizing search engine...", 0.65);
        if (service.triageServiceImpl() != null) {
            service.knowledgeBaseImpl().setEmbedder(service.triageServiceImpl().getModel());
            service.triageServiceImpl().setCentroid(service.knowledgeBaseImpl().getCentroid());
        }
    }

    private void provisionTriageModel(BootstrapState state, BootstrapService service) {
        if (!service.modelManager().isTriageModelReady()) {
            state.update("Downloading intent classifier...", 0.65);
            activeDownload = service.modelManager().downloadTriageModel();
            activeDownload.join();
        } else {
            state.update("Intent classifier ready", 0.70);
        }

        if (isInterrupted()) return;

        if (service.triageServiceImpl() != null) {
            service.triageServiceImpl().close();
        }
        TriageService triage = new TriageService(service.modelManager().getTriageModelPath());
        service.setTriageService(triage);
    }

    private void provisionTTSModel(BootstrapState state, BootstrapService service, String language) {
        if (!service.modelManager().isTTSModelReady()) {
            state.update("Downloading speech synthesis model...", 0.72);
            activeDownload = service.modelManager().downloadTTSModel();
            activeDownload.join();
        } else {
            state.update("Speech synthesis model ready", 0.74);
        }

        if (isInterrupted()) return;

        state.update("Loading speech synthesis...", 0.75);
        if (service.ttsServiceImpl() != null) {
            service.ttsServiceImpl().shutdown();
        }
        SherpaTTSService tts = new SherpaTTSService(service.modelManager().getTTSModelPath(), language);
        service.setTtsService(tts);
    }

    private void initBrainService(BootstrapState state, BootstrapService service, 
                                  SystemCapability.AIModelProfile profile, String customUrl, String language) {
        state.update("Loading AI brain...", 0.80);
        if (service.brainServiceImpl() != null) {
            service.brainServiceImpl().close();
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
            service.brainServiceImpl().generateResponse(primer, "Warmup — no real protocol needed.", language, null,
                    new InferenceListener() {
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
