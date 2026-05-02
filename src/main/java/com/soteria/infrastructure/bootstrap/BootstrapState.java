package com.soteria.infrastructure.bootstrap;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encapsulates the UI state and synchronization futures for the bootstrap process.
 * Separating this ensures that UI property management doesn't clutter the main service logic.
 */
public class BootstrapState {
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Idle");
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(0.0);
    private final ReadOnlyBooleanWrapper readyToChat = new ReadOnlyBooleanWrapper(false);

    // Low-level future for internal synchronization
    private final AtomicReference<CompletableFuture<Void>> readyFuture = new AtomicReference<>(new CompletableFuture<>());

    public void update(String text, double pct) {
        Runnable updateTask = () -> {
            status.set(text);
            progress.set(pct);
        };

        executeInFxThread(updateTask);
    }

    /** Marks provisioning finished: localized status line, full progress, chat unlocked. */
    public void signalProvisioningComplete(String localizedStatusText) {
        executeInFxThread(() -> {
            status.set(localizedStatusText);
            progress.set(1.0);
            readyToChat.set(true);
        });
    }

    public void setReadyToChat(boolean ready) {
        executeInFxThread(() -> readyToChat.set(ready));
    }

    private void executeInFxThread(Runnable task) {
        try {
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
        } catch (IllegalStateException _) {
            // JavaFX Platform not initialized (common in unit tests)
            task.run();
        }
    }

    public void completeReadyFuture() {
        readyFuture.get().complete(null);
    }

    public void completeReadyFutureExceptionally(Throwable t) {
        readyFuture.get().completeExceptionally(t);
    }

    public void resetReadyFuture() {
        if (readyFuture.get().isDone()) {
            readyFuture.set(new CompletableFuture<>());
        }
    }

    public CompletableFuture<Void> getReadyFuture() {
        return readyFuture.get();
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

    public double getProgress() {
        return progress.get();
    }
}
