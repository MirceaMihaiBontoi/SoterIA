package com.soteria.infrastructure.intelligence.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.soteria.infrastructure.intelligence.system.ModelAssets.*;

/**
 * Facade for AI model management.
 * Delegates specialized tasks to focused components while maintaining 
 * backward compatibility for the rest of the system.
 */
public class ModelManager {
    private static final Logger logger = Logger.getLogger(ModelManager.class.getName());

    // Public constants maintained for API compatibility
    public static final int STT_SAMPLE_RATE = ModelAssets.STT_SAMPLE_RATE;
    public static final int STT_CHANNELS = ModelAssets.STT_CHANNELS;
    public static final int STT_BIT_DEPTH = ModelAssets.STT_BIT_DEPTH;
    public static final int VAD_WINDOW_SIZE = ModelAssets.VAD_WINDOW_SIZE;

    private final ModelPathResolver pathResolver;
    private final ModelFileDownloader downloader;
    private final ModelFileExtractor extractor;

    public ModelManager(SystemCapability capability) {
        this.pathResolver = new ModelPathResolver(capability);
        this.downloader = new ModelFileDownloader();
        this.extractor = new ModelFileExtractor();
    }

    /**
     * Internal constructor for testing.
     */
    public ModelManager(SystemCapability capability, Path modelBasePath) {
        this.pathResolver = new ModelPathResolver(capability, modelBasePath);
        this.downloader = new ModelFileDownloader();
        this.extractor = new ModelFileExtractor();
    }

    public Path getModelBasePath() {
        return pathResolver.getModelBasePath();
    }

    public Path getBrainModelPath() {
        return pathResolver.getBrainModelPath();
    }

    public Path getBrainModelPath(SystemCapability.AIModelProfile profile) {
        return pathResolver.getBrainModelPath(profile);
    }

    public Path getSTTModelPath() {
        return pathResolver.getSTTModelPath();
    }

    public Path getVADModelPath() {
        return pathResolver.getVADModelPath();
    }

    public Path getKWSModelPath() {
        return pathResolver.getKWSModelPath();
    }

    public Path getTriageModelPath() {
        return pathResolver.getTriageModelPath();
    }

    public Path getTTSModelPath() {
        return pathResolver.getTTSModelPath();
    }

    public Path getEmbeddingModelPath() {
        return pathResolver.getEmbeddingModelPath();
    }

    public Path getKBIndexPath() {
        return pathResolver.getKBIndexPath();
    }

    public boolean isBrainModelReady() {
        return isBrainModelReady(pathResolver.getCapability().getRecommendedProfile());
    }

    public boolean isBrainModelReady(SystemCapability.AIModelProfile profile) {
        return pathResolver.isBrainModelReady(profile);
    }

    public boolean isSTTModelReady() {
        return pathResolver.isSTTModelReady();
    }

    public boolean isVADModelReady() {
        return pathResolver.isVADModelReady();
    }

    public boolean isKWSModelReady() {
        return pathResolver.isKWSModelReady();
    }

    public boolean isTriageModelReady() {
        return pathResolver.isTriageModelReady();
    }

    public boolean isTTSModelReady() {
        return pathResolver.isTTSModelReady();
    }

    public boolean isEmbeddingModelReady() {
        return pathResolver.isEmbeddingModelReady();
    }

    public CompletableFuture<Path> downloadBrainModel(SystemCapability.AIModelProfile profile) {
        String url = downloader.getBrainModelUrl(profile);
        String fileName = pathResolver.getBrainModelFileName(profile);
        Path target = pathResolver.getModelBasePath().resolve(fileName);

        if (Files.exists(target)) {
            return CompletableFuture.completedFuture(target);
        }
        return downloader.downloadFile(url, target);
    }

    public CompletableFuture<Path> downloadEmbeddingModel() {
        return CompletableFuture.completedFuture(getEmbeddingModelPath());
    }

    public CompletableFuture<Path> downloadSTTModel() {
        Path target = getSTTModelPath();
        if (isSTTModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        Path tarFile = getModelBasePath().resolve(STT_MODEL_NAME + TAR_BZ2_EXT);
        return downloader.downloadFile(STT_MODEL_URL, tarFile)
                .thenApply(tar -> {
                    extractor.extractTarBz2(tar, getModelBasePath());
                    cleanup(tar);
                    return target;
                });
    }

    public CompletableFuture<Path> downloadTriageModel() {
        return CompletableFuture.completedFuture(getTriageModelPath());
    }

    public CompletableFuture<Path> downloadTTSModel() {
        Path target = getTTSModelPath();
        if (isTTSModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        Path tarFile = getModelBasePath().resolve(TTS_MODEL_NAME + TAR_BZ2_EXT);
        return downloader.downloadFile(TTS_MODEL_URL, tarFile)
                .thenApply(tar -> {
                    extractor.extractTarBz2(tar, getModelBasePath());
                    cleanup(tar);
                    return target;
                });
    }

    public CompletableFuture<Path> downloadKWSModel() {
        Path target = getKWSModelPath();
        if (isKWSModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        Path tarFile = getModelBasePath().resolve(KWS_MODEL_NAME + TAR_BZ2_EXT);
        return downloader.downloadFile(KWS_MODEL_URL, tarFile)
                .thenApply(tar -> {
                    extractor.extractTarBz2(tar, getModelBasePath());
                    cleanup(tar);
                    return target;
                });
    }

    public CompletableFuture<Path> downloadVADModel() {
        Path target = getVADModelPath();
        if (isVADModelReady()) {
            return CompletableFuture.completedFuture(target);
        }
        return downloader.downloadFile(VAD_MODEL_URL, target);
    }

    private void cleanup(Path tar) {
        try {
            Files.deleteIfExists(tar);
        } catch (IOException e) {
            logger.log(Level.WARNING, CLEANUP_ERROR_MSG, e);
        }
    }

    public float getSTTVolumeBoost() {
        return ModelAssets.STT_VOLUME_BOOST;
    }

    public float getSTTVadThreshold() {
        return ModelAssets.STT_VAD_THRESHOLD;
    }

    public float getSTTMinSilenceDuration() {
        return ModelAssets.STT_MIN_SILENCE_DURATION;
    }

    public float getSTTMinSpeechDuration() {
        return ModelAssets.STT_MIN_SPEECH_DURATION;
    }
}
