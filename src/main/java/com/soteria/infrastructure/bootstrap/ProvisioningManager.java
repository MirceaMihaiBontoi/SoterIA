package com.soteria.infrastructure.bootstrap;

import com.soteria.core.domain.chat.ChatMessage;
import com.soteria.core.port.InferenceListener;
import com.soteria.core.port.LocalizationService;
import com.soteria.infrastructure.intelligence.system.SystemCapability;
import com.soteria.infrastructure.intelligence.stt.SherpaSTTService;
import com.soteria.infrastructure.intelligence.tts.SherpaTTSService;
import com.soteria.infrastructure.intelligence.triage.TriageService;
import com.soteria.infrastructure.intelligence.llm.LocalBrainService;
import com.soteria.infrastructure.intelligence.kws.WakeWordService;

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

    private static String loc(BootstrapService service, String key) {
        LocalizationService localization = service.localizationService();
        return localization != null ? localization.getMessage(key) : key;
    }

    private static String locFmt(BootstrapService service, String key, Object... args) {
        LocalizationService localization = service.localizationService();
        return localization != null ? localization.formatMessage(key, args) : key;
    }

    private String lastProvisioningKey;
    private Thread activeProvisioner;
    private CompletableFuture<?> activeDownload;

    public synchronized void start(BootstrapState state, BootstrapService service,
                                   SystemCapability.AIModelProfile profile, String language) {

        String key = profile.name() + "|" + language;

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
        activeProvisioner = new Thread(() -> runProvisioning(state, service, profile, language), "soteria-provisioner");
        activeProvisioner.setDaemon(true);
        activeProvisioner.start();
    }

    private void runProvisioning(BootstrapState state, BootstrapService service,
                                 SystemCapability.AIModelProfile profile, String language) {
        try {
            long totalStart = System.nanoTime();
            log.info("Starting provisioning sequence...");

            if (isInterrupted()) return;
            long stepStart = System.nanoTime();
            provisionSTT(state, service, language);
            long sttMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] STT provision: %d ms", sttMs));

            if (isInterrupted()) return;
            stepStart = System.nanoTime();
            provisionKWSModel(state, service);
            long kwsMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] KWS provision: %d ms", kwsMs));

            if (isInterrupted()) return;
            stepStart = System.nanoTime();
            provisionBrainModel(state, service, profile);
            long brainDlMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] Brain model download/check: %d ms", brainDlMs));

            if (isInterrupted()) return;
            stepStart = System.nanoTime();
            provisionTriageModel(state, service);
            long triageMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] Triage provision: %d ms", triageMs));

            if (isInterrupted()) return;
            stepStart = System.nanoTime();
            provisionTTSModel(state, service, language);
            long ttsMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] TTS provision: %d ms", ttsMs));

            if (isInterrupted()) return;
            stepStart = System.nanoTime();
            provisionKnowledgeBase(state, service);
            long kbMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] Knowledge Base provision: %d ms", kbMs));

            if (isInterrupted()) return;
            stepStart = System.nanoTime();
            initBrainService(state, service, profile, language);
            long brainInitMs = (System.nanoTime() - stepStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] Brain init + warmup: %d ms", brainInitMs));

            long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
            log.info(() -> String.format("[TIMING] ======= TOTAL PROVISIONING: %d ms (%.1f s) =======", totalMs, totalMs / 1000.0));

            state.signalProvisioningComplete(loc(service, "onboarding.provision.ready"));
            state.completeReadyFuture();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Provisioning failed during runProvisioning", e);
            handleProvisioningError(state, service, e);
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
        if (!service.modelManager().isSTTModelReady()) {
            state.update(loc(service, "onboarding.provision.stt_downloading"), 0.10);
            activeDownload = service.modelManager().downloadSTTModel();
            activeDownload.join();
        } else {
            state.update(loc(service, "onboarding.provision.stt_ready"), 0.15);
        }

        if (isInterrupted()) return;

        if (!service.modelManager().isVADModelReady()) {
            state.update(loc(service, "onboarding.provision.vad_downloading"), 0.20);
            activeDownload = service.modelManager().downloadVADModel();
            activeDownload.join();
        } else {
            state.update(loc(service, "onboarding.provision.vad_ready"), 0.25);
        }

        if (isInterrupted()) return;

        state.update(loc(service, "onboarding.provision.stt_loading"), 0.30);
        if (service.sttServiceImpl() != null) {
            service.sttServiceImpl().shutdown();
        }
        SherpaSTTService stt = new SherpaSTTService(service.modelManager().getSTTModelPath(), language, service.modelManager());
        service.setSttService(stt);
    }

    private void provisionKWSModel(BootstrapState state, BootstrapService service) throws IOException {
        if (!service.modelManager().isKWSModelReady()) {
            state.update(loc(service, "onboarding.provision.kws_downloading"), 0.35);
            activeDownload = service.modelManager().downloadKWSModel();
            activeDownload.join();
        } else {
            state.update(loc(service, "onboarding.provision.kws_ready"), 0.38);
        }

        if (isInterrupted()) return;

        state.update(loc(service, "onboarding.provision.kws_loading"), 0.39);
        if (service.wakeWordService() != null) {
            service.wakeWordService().shutdown();
        }
        WakeWordService kws = new WakeWordService(service.modelManager().getKWSModelPath());
        service.setWakeWordService(kws);
    }

    private void provisionBrainModel(BootstrapState state, BootstrapService service,
                                     SystemCapability.AIModelProfile profile) {
        if (!service.modelManager().isBrainModelReady(profile)) {
            String modelDisplay = loc(service, profile.getMessageKey());
            state.update(locFmt(service, "onboarding.provision.brain_downloading", modelDisplay), 0.40);
            activeDownload = service.modelManager().downloadBrainModel(profile);
            activeDownload.join();
        } else {
            state.update(loc(service, "onboarding.provision.brain_model_ready"), 0.60);
        }
    }

    private void provisionKnowledgeBase(BootstrapState state, BootstrapService service) {
        state.update(loc(service, "onboarding.provision.kb_optimizing"), 0.65);
        if (service.triageServiceImpl() != null) {
            service.knowledgeBaseImpl().setEmbedder(service.triageServiceImpl().getModel());
            service.triageServiceImpl().setCentroid(service.knowledgeBaseImpl().getCentroid());
        }
    }

    private void provisionTriageModel(BootstrapState state, BootstrapService service) {
        if (!service.modelManager().isTriageModelReady()) {
            state.update(loc(service, "onboarding.provision.triage_downloading"), 0.65);
            activeDownload = service.modelManager().downloadTriageModel();
            activeDownload.join();
        } else {
            state.update(loc(service, "onboarding.provision.triage_ready"), 0.70);
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
            state.update(loc(service, "onboarding.provision.tts_downloading"), 0.72);
            activeDownload = service.modelManager().downloadTTSModel();
            activeDownload.join();
        } else {
            state.update(loc(service, "onboarding.provision.tts_ready"), 0.74);
        }

        if (isInterrupted()) return;

        state.update(loc(service, "onboarding.provision.tts_loading"), 0.75);
        if (service.ttsServiceImpl() != null) {
            service.ttsServiceImpl().shutdown();
        }
        SherpaTTSService tts = new SherpaTTSService(service.modelManager().getTTSModelPath(), language);
        service.setTtsService(tts);
    }

    private void initBrainService(BootstrapState state, BootstrapService service,
                                  SystemCapability.AIModelProfile profile, String language) {
        state.update(loc(service, "onboarding.provision.brain_loading"), 0.80);
        if (service.brainServiceImpl() != null) {
            service.brainServiceImpl().close();
        }
        LocalBrainService brain = new LocalBrainService(service.modelManager().getBrainModelPath(profile), service.capability());
        service.setBrainService(brain);

        if (isInterrupted()) return;

        state.update(loc(service, "onboarding.provision.brain_warming"), 0.90);
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

    private void handleProvisioningError(BootstrapState state, BootstrapService service, Exception e) {
        if (isInterrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
            log.info("Provisioning task aborted cleanly.");
            return;
        }
        
        String errorMsg = e.getMessage();
        if (errorMsg == null && e.getCause() != null) errorMsg = e.getCause().getMessage();
        if (errorMsg == null) errorMsg = e.getClass().getSimpleName();

        log.log(Level.SEVERE, "Provisioning failed", e);
        state.update(locFmt(service, "onboarding.provision.setup_error", errorMsg), state.getProgress());
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
