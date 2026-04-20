package com.soteria.infrastructure.intelligence;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages AI assets on disk:
 *  - Vosk STT models (distributed as .zip, extracted on download).
 *  - LLM GGUF files (single-file format for llama.cpp).
 *
 * The brain backend is llama.cpp; models are GGUFs, which means **one file per
 * model** — no external data, no tokenizer side-files, no manifest juggling.
 */
public class ModelManager {
    private static final Logger logger = Logger.getLogger(ModelManager.class.getName());

    private static final String MODEL_DIR = "models";

    // Vosk (STT) — zipped bundles
    private static final String VOSK_ES_LITE_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
    private static final String VOSK_ES_PERF_URL = "https://alphacephei.com/vosk/models/vosk-model-es-0.42.zip";
    private static final String VOSK_EN_LITE_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String VOSK_EN_PERF_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip";

    // Brain (LLM) — GGUFs published by unsloth on HuggingFace.
    // TODO (SOTERIA-22): Migrate to Gemma 4 (gemma-4-E2B/E4B-it-GGUF) when kherud:llama >= 4.3.0
    //                    includes soporte for gemma4 architecture.
    private static final String LLM_ULTRA_LITE_URL  = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf";
    private static final String LLM_LITE_URL        = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q8_0.gguf";
    private static final String LLM_BALANCED_URL    = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf";
    private static final String LLM_PERFORMANCE_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf";
    private static final String LLM_ULTRA_URL       = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q8_0.gguf";

    private final SystemCapability capability;
    private final Path modelBasePath;

    public ModelManager(SystemCapability capability) {
        this.capability = capability;
        this.modelBasePath = Paths.get(System.getProperty("user.home"), ".soteria", MODEL_DIR);

        try {
            Files.createDirectories(modelBasePath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create models directory", e);
        }
    }

    private String getCustomFileName(String url) {
        return "custom-" + Integer.toHexString(url.hashCode()) + ".gguf";
    }

    /**
     * Returns the on-disk path for a brain model (GGUF file), whether it exists or not.
     */
    public Path getBrainModelPath() {
        return getBrainModelPath(capability.getRecommendedProfile(), null);
    }

    public Path getBrainModelPath(SystemCapability.AIModelProfile profile, String customUrl) {
        if (customUrl != null && !customUrl.isBlank()) {
            return modelBasePath.resolve(getCustomFileName(customUrl));
        }
        return modelBasePath.resolve(getBrainModelFileName(profile));
    }

    /**
     * Returns the on-disk path for a Vosk model directory, whether it exists or not.
     */
    public Path getVoskModelPath(String language) {
        return modelBasePath.resolve(getVoskModelName(language));
    }

    public boolean isBrainModelReady() {
        return isBrainModelReady(capability.getRecommendedProfile(), null);
    }

    public boolean isBrainModelReady(SystemCapability.AIModelProfile profile, String customUrl) {
        return Files.exists(getBrainModelPath(profile, customUrl));
    }

    public boolean isVoskModelReady(String language) {
        return Files.exists(getVoskModelPath(language));
    }

    /**
     * Downloads a specific GGUF model profile.
     */
    public CompletableFuture<Path> downloadBrainModel(SystemCapability.AIModelProfile profile) {
        return downloadBrainModel(profile, null);
    }

    /**
     * Downloads a GGUF model from a profile or a custom URL.
     */
    public CompletableFuture<Path> downloadBrainModel(SystemCapability.AIModelProfile profile, String customUrl) {
        if (customUrl != null && !customUrl.isBlank()) {
            Path target = modelBasePath.resolve(getCustomFileName(customUrl));
            if (Files.exists(target)) {
                return CompletableFuture.completedFuture(target);
            }
            return downloadFile(customUrl, target);
        }

        String url = getBrainModelUrl(profile);
        String fileName = getBrainModelFileName(profile);
        Path target = modelBasePath.resolve(fileName);
        
        if (Files.exists(target)) {
            return CompletableFuture.completedFuture(target);
        }
        return downloadFile(url, target);
    }

    /**
     * Downloads and extracts the Vosk bundle for the given language. No-op if present.
     */
    public CompletableFuture<Path> downloadVoskModel(String language) {
        Path target = getVoskModelPath(language);
        if (Files.exists(target)) {
            return CompletableFuture.completedFuture(target);
        }
        Path zipFile = modelBasePath.resolve(getVoskModelName(language) + ".zip");
        return downloadFile(getVoskModelUrl(language), zipFile)
                .thenApply(zip -> {
                    extractZip(zip, modelBasePath);
                    try {
                        Files.deleteIfExists(zip);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Failed to delete zip after extraction", e);
                    }
                    return target;
                });
    }

    private CompletableFuture<Path> downloadFile(String url, Path finalTarget) {
        Path partFile = Paths.get(finalTarget.toString() + ".part");
        logger.log(Level.INFO, "Downloading {0} -> {1}", new Object[]{url, partFile});

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
        } catch (IOException ignored) {
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
                        return CompletableFuture.failedFuture(new IOException("Download failed. HTTP " + status + " for " + url));
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

    public String getVoskModelUrl(String language) {
        boolean isSpanish = language.equalsIgnoreCase("Spanish");
        if (isSpanish) {
            return capability.isLowPowerDevice() ? VOSK_ES_LITE_URL : VOSK_ES_PERF_URL;
        }
        return capability.isLowPowerDevice() ? VOSK_EN_LITE_URL : VOSK_EN_PERF_URL;
    }

    public String getVoskModelName(String language) {
        boolean isSpanish = language.equalsIgnoreCase("Spanish");
        if (isSpanish) {
            return capability.isLowPowerDevice() ? "vosk-model-small-es-0.42" : "vosk-model-es-0.42";
        }
        return capability.isLowPowerDevice() ? "vosk-model-small-en-us-0.15" : "vosk-model-en-us-0.22";
    }

    public String getBrainModelUrl() {
        return getBrainModelUrl(capability.getRecommendedProfile());
    }

    public String getBrainModelUrl(SystemCapability.AIModelProfile profile) {
        return switch (profile) {
            case ULTRA_LITE -> LLM_ULTRA_LITE_URL;
            case LITE -> LLM_LITE_URL;
            case BALANCED -> LLM_BALANCED_URL;
            case PERFORMANCE -> LLM_PERFORMANCE_URL;
            case ULTRA -> LLM_ULTRA_URL;
        };
    }

    public String getBrainModelFileName() {
        return getBrainModelFileName(capability.getRecommendedProfile());
    }

    public String getBrainModelFileName(SystemCapability.AIModelProfile profile) {
        return switch (profile) {
            case ULTRA_LITE -> "gemma-3-1b-it-Q4_K_M.gguf";
            case LITE -> "gemma-3-1b-it-Q8_0.gguf";
            case BALANCED -> "gemma-3-4b-it-Q4_K_M.gguf";
            case PERFORMANCE -> "gemma-3-4b-it-Q4_K_M.gguf";
            case ULTRA -> "gemma-3-4b-it-Q8_0.gguf";
        };
    }

    private void extractZip(Path zipFile, Path destDir) {
        logger.log(Level.INFO, "Extracting: {0}", zipFile.getFileName());
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream os = Files.newOutputStream(newPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to extract zip", e);
            throw new UncheckedIOException(e);
        }
    }

    public Path getModelBasePath() {
        return modelBasePath;
    }
}
