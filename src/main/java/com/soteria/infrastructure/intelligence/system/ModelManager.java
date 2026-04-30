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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    // STT Model — Lightweight Multilingual ASR (99 languages)
    private static final String STT_MODEL_NAME = "sherpa-onnx-whisper-small";
    private static final String STT_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2";

    // Brain (LLM) — GGUFs published by unsloth on HuggingFace.
    // kherud:llama >= 4.3.0 when available
    // includes soporte for gemma4 architecture.
    private static final String LLM_STABLE_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf";
    private static final String LLM_PRO_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q8_0.gguf";

    // Triage (Intent) Model — Specialized crisis/emergency classifier (Local)
    private static final String TRIAGE_MODEL_NAME = "soteria-triage-v1.gguf";

    // TTS Model — Kokoro-82M for multilingual speech synthesis
    private static final String TTS_MODEL_NAME = "kokoro-multi-lang-v1_0";
    private static final String TTS_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2";

    // KWS Model — sherpa-onnx KeywordSpotter (3M parameters, Bilingual ZH/EN)
    private static final String KWS_MODEL_NAME = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20";
    private static final String KWS_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2";

    // VAD Model — Silero VAD for robust speech detection
    private static final String VAD_MODEL_NAME = "silero_vad.onnx";
    private static final String VAD_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private static final String TAR_BZ2_EXT = ".tar.bz2";
    private static final String CLEANUP_ERROR_MSG = "Failed to delete tar.bz2 after extraction";

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


    public boolean isSTTModelReady() {
        Path path = getSTTModelPath();
        if (!Files.exists(path) || !Files.isDirectory(path)) return false;
        
        try (var stream = Files.list(path)) {
            List<Path> files = stream.toList();
            boolean hasEncoder = files.stream().anyMatch(p -> p.getFileName().toString().endsWith("-encoder.onnx") || p.getFileName().toString().endsWith("-encoder.int8.onnx"));
            boolean hasDecoder = files.stream().anyMatch(p -> p.getFileName().toString().endsWith("-decoder.onnx") || p.getFileName().toString().endsWith("-decoder.int8.onnx"));
            boolean hasTokens = files.stream().anyMatch(p -> p.getFileName().toString().endsWith("-tokens.txt") || p.getFileName().toString().equals("tokens.txt"));
            
            return hasEncoder && hasDecoder && hasTokens;
        } catch (IOException _) {
            return false;
        }
    }

    public Path getSTTModelPath() {
        return modelBasePath.resolve(STT_MODEL_NAME);
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

    public boolean isTTSModelReady() {
        Path path = getTTSModelPath();
        // The Kokoro model needs model.onnx, voices.bin and tokens.txt to be considered ready
        return Files.exists(path) && Files.isDirectory(path) 
            && Files.exists(path.resolve("model.onnx"))
            && Files.exists(path.resolve("voices.bin"));
    }

    public Path getTTSModelPath() {
        return modelBasePath.resolve(TTS_MODEL_NAME);
    }

    public boolean isKWSModelReady() {
        Path path = getKWSModelPath();
        return Files.exists(path) && Files.isDirectory(path) 
            && Files.exists(path.resolve("encoder-epoch-13-avg-2-chunk-16-left-64.onnx"));
    }

    public Path getKWSModelPath() {
        return modelBasePath.resolve(KWS_MODEL_NAME);
    }

    public boolean isVADModelReady() {
        return Files.exists(getVADModelPath());
    }

    public Path getVADModelPath() {
        return modelBasePath.resolve(VAD_MODEL_NAME);
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

    public CompletableFuture<Path> downloadSTTModel() {
        Path target = getSTTModelPath();
        if (isSTTModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        Path tarFile = modelBasePath.resolve(STT_MODEL_NAME + TAR_BZ2_EXT);
        return downloadFile(STT_MODEL_URL, tarFile)
                .thenApply(tar -> {
                    extractTarBz2(tar, modelBasePath);
                    try {
                        Files.deleteIfExists(tar);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, CLEANUP_ERROR_MSG, e);
                    }
                    return target;
                });
    }

    /**
     * Triage model is now local-only. No download logic.
     */
    public CompletableFuture<Path> downloadTriageModel() {
        return CompletableFuture.completedFuture(getTriageModelPath());
    }

    /**
     * Downloads the Kokoro-82M TTS bundle for multilingual speech synthesis.
     */
    public CompletableFuture<Path> downloadTTSModel() {
        Path target = getTTSModelPath();
        if (isTTSModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        Path tarFile = modelBasePath.resolve(TTS_MODEL_NAME + TAR_BZ2_EXT);
        return downloadFile(TTS_MODEL_URL, tarFile)
                .thenApply(tar -> {
                    extractTarBz2(tar, modelBasePath);
                    try {
                        Files.deleteIfExists(tar);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, CLEANUP_ERROR_MSG, e);
                    }
                    return target;
                });
    }

    /**
     * Downloads the sherpa-onnx KWS model for wake-word detection.
     */
    public CompletableFuture<Path> downloadKWSModel() {
        Path target = getKWSModelPath();
        if (isKWSModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        Path tarFile = modelBasePath.resolve(KWS_MODEL_NAME + TAR_BZ2_EXT);
        return downloadFile(KWS_MODEL_URL, tarFile)
                .thenApply(tar -> {
                    extractTarBz2(tar, modelBasePath);
                    try {
                        Files.deleteIfExists(tar);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, CLEANUP_ERROR_MSG, e);
                    }
                    return target;
                });
    }

    public CompletableFuture<Path> downloadVADModel() {
        Path target = getVADModelPath();
        if (isVADModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        return downloadFile(VAD_MODEL_URL, target);
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


    private void extractTarBz2(Path tarFile, Path destDir) {
        logger.log(Level.INFO, "Extracting: {0} (this may take a minute...)", tarFile.getFileName());
        try (InputStream fi = new java.io.BufferedInputStream(Files.newInputStream(tarFile), 128 * 1024);
             InputStream bi = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(fi);
             InputStream bbi = new java.io.BufferedInputStream(bi, 128 * 1024);
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream ti = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bbi)) {

            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                Path newPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(newPath), 128 * 1024)) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = ti.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }
            logger.log(Level.INFO, "Extraction complete: {0}", tarFile.getFileName());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to extract tar.bz2", e);
            throw new UncheckedIOException(e);
        }
    }

    public Path getModelBasePath() {
        return modelBasePath;
    }

    // --- STT Configuration ---
    public static final int STT_SAMPLE_RATE = 16000;
    public static final int STT_CHANNELS = 1;
    public static final int STT_BIT_DEPTH = 16;
    public static final int VAD_WINDOW_SIZE = 512;

    /**
     * Volume multiplier for microphone input. 
     * Higher values increase sensitivity but also noise.
     */
    public float getSTTVolumeBoost() {
        return 1.0f; 
    }

    /**
     * Threshold for Silero VAD (0.0 to 1.0).
     * Higher is more strict.
     */
    public float getSTTVadThreshold() {
        return 0.35f;
    }

    /**
     * Milliseconds of silence required to trigger an endpoint (sentence end).
     */
    public float getSTTMinSilenceDuration() {
        return 0.25f;
    }

    /**
     * Minimum duration of speech in seconds to consider it valid.
     */
    public float getSTTMinSpeechDuration() {
        return 0.5f;
    }
}
