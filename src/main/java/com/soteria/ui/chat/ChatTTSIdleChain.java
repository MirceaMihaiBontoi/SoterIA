package com.soteria.ui.chat;

import com.soteria.core.port.TTS;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Chains background waits until TTS (text-to-speech) playback has finished, so idle UI updates are not racy across
 * overlapping completions.
 */
final class ChatTTSIdleChain {

    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private final Object lock = new Object();

    /**
     * After any prior step in this chain, polls until the given {@link TTS} backend stops speaking (no-op when
     * {@code ttsService} is {@code null}), then runs {@code javafxWork} on the JavaFX application thread.
     *
     * @param ttsService TTS backend; may be {@code null}
     * @param log        receives fine-level noise when a chain step fails
     * @param javafxWork runnable on the application thread (typically face / subtitle updates)
     */
    void enqueueAfterSpeechSilence(TTS ttsService, Logger log, Runnable javafxWork) {
        synchronized (lock) {
            tail = tail
                    .handle((ok, err) -> null)
                    .thenRunAsync(() -> {
                        TTS active = ttsService;
                        if (active == null) {
                            return;
                        }
                        try {
                            while (active.isSpeaking()) {
                                Thread.sleep(80);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .thenRun(() -> Platform.runLater(javafxWork))
                    .whenComplete((r, err) -> {
                        if (err != null) {
                            log.log(Level.FINE, "TTS idle chain step failed (ignored)", err.getCause());
                        }
                    });
        }
    }
}
