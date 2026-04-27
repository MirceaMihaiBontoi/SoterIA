package com.soteria.infrastructure.intelligence.system;

import java.io.FileInputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages AI assets on disk:
 * - Vosk STT models (distributed as .zip, extracted on download).
 * - LLM GGUF files (single-file format for llama.cpp).
 *
 * The brain backend is llama.cpp; models are GGUFs, which means **one file per
 * model** — no external data, no tokenizer side-files, no manifest juggling.
 */
public class ModelManager {
    private static final Logger logger = Logger.getLogger(ModelManager.class.getName());

    private static final String MODEL_DIR = "models";

    // Vosk constants for STT
    private static final String DEFAULT_LANG = "ENGLISH";
    private static final String DEFAULT_VOSK_VERSION = "en-us-0.22";
    private static final String VOSK_MODEL_PREFIX = "vosk-model-";
    private static final String VOSK_SMALL_PREFIX = "vosk-model-small-";

    // Brain (LLM) — GGUFs published by unsloth on HuggingFace.
    // kherud:llama >= 4.3.0 when available
    // includes soporte for gemma4 architecture.
    private static final String LLM_STABLE_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf";
    private static final String LLM_PRO_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q8_0.gguf";

    // Triage (Intent) Model — Specialized crisis/emergency classifier (Local)
    private static final String TRIAGE_MODEL_NAME = "soteria-triage-v1.gguf";

    private final SystemCapability capability;
    private final Path modelBasePath;

    public ModelManager(SystemCapability capability) {
        this(capability, Paths.get(System.getProperty("user.home"), ".soteria", MODEL_DIR));
    }

    /**
     * Internal constructor for testing.
     */
    ModelManager(SystemCapability capability, Path modelBasePath) {
        this.capability = capability;
        this.modelBasePath = modelBasePath;

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
     * Returns the on-disk path for a brain model (GGUF file), whether it exists or
     * not.
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
     * Returns the on-disk path for the embedding model.
     */
    public Path getEmbeddingModelPath() {
        return getTriageModelPath();
    }

    /**
     * Returns the on-disk path for a Vosk model directory, whether it exists or
     * not.
     */
    public Path getVoskModelPath(String language) {
        return modelBasePath.resolve(getVoskModelName(language));
    }

    /**
     * Gets the path to the persistent Knowledge Base index.
     */
    public Path getKBIndexPath() {
        Path indexPath = modelBasePath.getParent().resolve("index");
        try {
            Files.createDirectories(indexPath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create index directory", e);
        }
        return indexPath;
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

    public boolean isEmbeddingModelReady() {
        return Files.exists(getEmbeddingModelPath());
    }

    public boolean isTriageModelReady() {
        return Files.exists(getTriageModelPath());
    }

    public Path getTriageModelPath() {
        return modelBasePath.resolve(TRIAGE_MODEL_NAME);
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
     * Downloads the multilingual embedding model.
     * Note: Currently uses the same model as Triage.
     */
    public CompletableFuture<Path> downloadEmbeddingModel() {
        return CompletableFuture.completedFuture(getEmbeddingModelPath());
    }

    /**
     * Triage model is now local-only. No download logic.
     */
    public CompletableFuture<Path> downloadTriageModel() {
        return CompletableFuture.completedFuture(getTriageModelPath());
    }

    /**
     * Downloads and extracts the Vosk bundle for the given language. No-op if
     * present.
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

    private static final String VOSK_BASE_URL = "https://alphacephei.com/vosk/models/";

    private static final java.util.Map<String, String> VOSK_LANG_MAP = java.util.Map.ofEntries(
            java.util.Map.entry("SPANISH", "es-0.42"),
            java.util.Map.entry(DEFAULT_LANG, DEFAULT_VOSK_VERSION),
            java.util.Map.entry("CHINESE", "cn-0.22"),
            java.util.Map.entry("FRENCH", "fr-0.22"),
            java.util.Map.entry("GERMAN", "de-0.15"),
            java.util.Map.entry("RUSSIAN", "ru-0.42"),
            java.util.Map.entry("JAPANESE", "ja-0.22"),
            java.util.Map.entry("PORTUGUESE", "pt-fb-0.4"),
            java.util.Map.entry("HINDI", "hi-0.22"),
            java.util.Map.entry("ARABIC", "ar-0.22"),
            java.util.Map.entry("ITALIAN", "it-0.22"),
            java.util.Map.entry("TURKISH", "tr-0.3"),
            java.util.Map.entry("VIETNAMESE", "vn-0.4"),
            java.util.Map.entry("KOREAN", "ko-0.22"));

    public String getVoskModelUrl(String language) {
        String key = language.toUpperCase();
        String version = VOSK_LANG_MAP.getOrDefault(key, DEFAULT_VOSK_VERSION);
        String prefix = capability.isLowPowerDevice() ? VOSK_SMALL_PREFIX : VOSK_MODEL_PREFIX;

        // Handle special case where full model doesn't follow the small/full naming
        // pattern perfectly
        if (key.equals(DEFAULT_LANG) && !capability.isLowPowerDevice()) {
            return VOSK_BASE_URL + VOSK_MODEL_PREFIX + DEFAULT_VOSK_VERSION + ".zip";
        }

        return VOSK_BASE_URL + prefix + version + ".zip";
    }

    public String getVoskModelName(String language) {
        String key = language.toUpperCase();
        String version = VOSK_LANG_MAP.getOrDefault(key, DEFAULT_VOSK_VERSION);
        String prefix = capability.isLowPowerDevice() ? VOSK_SMALL_PREFIX : VOSK_MODEL_PREFIX;

        if (key.equals(DEFAULT_LANG) && !capability.isLowPowerDevice()) {
            return VOSK_MODEL_PREFIX + DEFAULT_VOSK_VERSION;
        }

        return prefix + version;
    }

    public String getBrainModelUrl() {
        return getBrainModelUrl(capability.getRecommendedProfile());
    }

    public String getBrainModelUrl(SystemCapability.AIModelProfile profile) {
        return switch (profile) {
            case STABLE -> LLM_STABLE_URL;
            case EXPERT -> LLM_PRO_URL;
            default -> LLM_STABLE_URL;
        };
    }

    public String getBrainModelFileName() {
        return getBrainModelFileName(capability.getRecommendedProfile());
    }

    public String getBrainModelFileName(SystemCapability.AIModelProfile profile) {
        return switch (profile) {
            case STABLE -> "gemma-3-4b-it-Q4_K_M.gguf";
            case EXPERT -> "gemma-3-4b-it-Q8_0.gguf";
            default -> "gemma-3-4b-it-Q4_K_M.gguf";
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
