package com.soteria.infrastructure.intelligence.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.soteria.infrastructure.intelligence.system.ModelAssets.*;

/**
 * Resolves local file system paths for AI models and checks for their existence.
 */
public class ModelPathResolver {
    private static final Logger logger = Logger.getLogger(ModelPathResolver.class.getName());

    private final SystemCapability capability;
    private final Path modelBasePath;

    public ModelPathResolver(SystemCapability capability) {
        this(capability, Paths.get(System.getProperty("user.home"), ".soteria", MODEL_DIR));
    }

    public ModelPathResolver(SystemCapability capability, Path modelBasePath) {
        this.capability = capability;
        this.modelBasePath = modelBasePath;
        ensureDirectoryExists();
    }

    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(modelBasePath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create models directory", e);
        }
    }

    public SystemCapability getCapability() {
        return capability;
    }

    public Path getModelBasePath() {
        return modelBasePath;
    }

    public Path getBrainModelPath() {
        return getBrainModelPath(capability.getRecommendedProfile());
    }

    public Path getBrainModelPath(SystemCapability.AIModelProfile profile) {
        return modelBasePath.resolve(getBrainModelFileName(profile));
    }

    public Path getSTTModelPath() {
        return modelBasePath.resolve(STT_MODEL_NAME);
    }

    public Path getVADModelPath() {
        return modelBasePath.resolve(VAD_MODEL_NAME);
    }

    public Path getKWSModelPath() {
        return modelBasePath.resolve(KWS_MODEL_NAME);
    }

    public Path getTriageModelPath() {
        return modelBasePath.resolve(TRIAGE_MODEL_NAME);
    }

    public Path getTTSModelPath() {
        return modelBasePath.resolve(TTS_MODEL_NAME);
    }

    public Path getEmbeddingModelPath() {
        return getTriageModelPath();
    }

    public Path getKBIndexPath() {
        Path indexPath = modelBasePath.getParent().resolve("index");
        try {
            Files.createDirectories(indexPath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create index directory", e);
        }
        return indexPath;
    }

    public boolean isBrainModelReady(SystemCapability.AIModelProfile profile) {
        return Files.exists(getBrainModelPath(profile));
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

    public boolean isVADModelReady() {
        return Files.exists(getVADModelPath());
    }

    public boolean isKWSModelReady() {
        Path path = getKWSModelPath();
        return Files.exists(path) && Files.isDirectory(path) 
            && Files.exists(path.resolve("encoder-epoch-13-avg-2-chunk-16-left-64.onnx"));
    }

    public boolean isTriageModelReady() {
        return Files.exists(getTriageModelPath());
    }

    public boolean isTTSModelReady() {
        Path path = getTTSModelPath();
        return Files.exists(path) && Files.isDirectory(path) 
            && Files.exists(path.resolve("model.onnx"))
            && Files.exists(path.resolve("voices.bin"));
    }

    public boolean isEmbeddingModelReady() {
        return Files.exists(getEmbeddingModelPath());
    }

    public String getBrainModelFileName(SystemCapability.AIModelProfile profile) {
        return switch (profile) {
            case LITE -> "gemma-4-E2B-it-Q4_K_M.gguf";
            case STABLE -> "gemma-4-E4B-it-Q4_K_M.gguf";
            case EXPERT -> "gemma-4-E4B-it-Q8_0.gguf";
        };
    }
}
