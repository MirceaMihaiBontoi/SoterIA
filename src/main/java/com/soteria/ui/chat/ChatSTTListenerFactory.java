package com.soteria.ui.chat;

import com.soteria.core.port.STTListener;
import javafx.application.Platform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.soteria.ui.view.ChatViewManager;
import com.soteria.ui.view.SoterIAFace;

/**
 * Factory for the STT (speech-to-text) {@link STTListener} used during mic capture, so {@link ChatController} stays
 * free of a large anonymous class.
 */
final class ChatSTTListenerFactory {

    /**
     * Configuration for {@link #create(Params)}.
     */
    record Params(
            String instanceId,
            Logger logger,
            ChatOutboundDedupe outboundDedupe,
            ChatViewManager viewManager,
            SoterIAFace face,
            AtomicBoolean haltedAssistantOnPartial,
            Runnable stopRecording,
            Runnable interruptOngoingGeneration,
            Consumer<String> onTranscriptAccepted,
            Supplier<String> subtitleListening,
            java.util.function.Function<Throwable, String> micErrorSubtitle) {
    }

    private ChatSTTListenerFactory() {
    }

    /**
     * @param p listener configuration; {@code p.onTranscriptAccepted()} runs on the FX thread after dedupe
     */
    static STTListener create(Params p) {
        return new MicCaptureSttListener(p);
    }

    private static final class MicCaptureSttListener implements STTListener {

        private final Params p;

        MicCaptureSttListener(Params p) {
            this.p = p;
        }

        @Override
        public void onResult(String text) {
            if (text.isEmpty()) {
                p.logger().log(Level.INFO, "[{0}] STT: Empty result received.", p.instanceId());
                Platform.runLater(p.stopRecording());
                return;
            }
            p.logger().log(Level.INFO, "[{0}] STT Result: ''{1}''", new Object[]{p.instanceId(), text});
            if (ChatInputGuards.isWakePhraseEchoTranscript(text)) {
                p.logger().log(Level.INFO,
                        "[{0}] STT: Ignored wake-word ''SoterIA'' in result stream. Cleaning up.",
                        p.instanceId());
                Platform.runLater(p.stopRecording());
                return;
            }
            Platform.runLater(() -> onResultFx(text));
        }

        private void onResultFx(String text) {
            if (!p.outboundDedupe().tryAccept(text, p.logger(), p.instanceId(), "[{0}] Ignored STT duplicate submit.")) {
                p.stopRecording().run();
                return;
            }
            p.logger().log(Level.INFO, "[{0}] Processing message: ''{1}''", new Object[]{p.instanceId(), text});
            p.onTranscriptAccepted().accept(text);
        }

        @Override
        public void onPartialResult(String text) {
            Platform.runLater(() -> {
                if (text != null && !text.isBlank() && p.haltedAssistantOnPartial().compareAndSet(false, true)) {
                    p.interruptOngoingGeneration().run();
                    p.face().setState(SoterIAFace.State.LISTENING);
                    p.viewManager().setSubtitle(p.subtitleListening().get());
                    p.logger().log(Level.INFO,
                            "[{0}] STT partial: interrupted assistant TTS / streaming inference.",
                            p.instanceId());
                }
                p.viewManager().setPartialTranscript(text != null ? text : "");
            });
        }

        @Override
        public void onError(Throwable t) {
            Platform.runLater(() -> {
                p.stopRecording().run();
                p.viewManager().setSubtitle(p.micErrorSubtitle().apply(t));
            });
        }
    }
}
