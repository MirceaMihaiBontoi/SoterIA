package com.soteria.infrastructure.intelligence.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.soteria.infrastructure.intelligence.system.ModelAssets.*;

/**
 * Handles network-bound download logic for AI models.
 * Implements HTTP Range-based downloads to allow resuming interrupted transfers.
 */
public class ModelFileDownloader {
    private static final Logger logger = Logger.getLogger(ModelFileDownloader.class.getName());

    public CompletableFuture<Path> downloadFile(String url, Path finalTarget) {
        Path partFile = Paths.get(finalTarget.toString() + ".part");
        logger.log(Level.INFO, "Downloading {0} -> {1}", new Object[] { url, partFile });

        try {
            Files.createDirectories(partFile.getParent());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not prepare target dir: {0}", e.getMessage());
        }

        long existingSize = 0;
        try {
            if (Files.exists(partFile)) {
                existingSize = Files.size(partFile);
            }
        } catch (IOException _) {
            // Safe to ignore: if we can't read the size, we'll just start fresh (0 bytes)
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SoterIA/1.0 (llama.cpp ModelManager)")
                .header("Accept", "*/*");

        if (existingSize > 0) {
            logger.log(Level.INFO, "Resuming download from byte {0}", existingSize);
            requestBuilder.header("Range", "bytes=" + existingSize + "-");
        }

        HttpRequest request = requestBuilder.GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status != 200 && status != 206) {
                        return CompletableFuture
                                .failedFuture(new IOException("Download failed. HTTP " + status + " for " + url));
                    }

                    boolean append = (status == 206);
                    return CompletableFuture.runAsync(() -> {
                        try (InputStream is = response.body()) {
                            writeStreamToFile(is, partFile, append);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }).thenApply(v -> finalizeDownload(partFile, finalTarget));
                });
    }

    private void writeStreamToFile(InputStream is, Path target, boolean append) throws IOException {
        try (OutputStream os = Files.newOutputStream(target,
                append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
    }

    private Path finalizeDownload(Path partFile, Path finalTarget) {
        try {
            Files.move(partFile, finalTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.log(Level.INFO, "Download success: {0}", finalTarget);
            return finalTarget;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String getBrainModelUrl(SystemCapability.AIModelProfile profile) {
        return switch (profile) {
            case STABLE -> LLM_STABLE_URL;
            case EXPERT -> LLM_PRO_URL;
            default -> LLM_STABLE_URL;
        };
    }
}
