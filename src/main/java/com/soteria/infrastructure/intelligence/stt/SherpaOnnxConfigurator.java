package com.soteria.infrastructure.intelligence.stt;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import com.soteria.infrastructure.intelligence.system.ModelManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Factory for sherpa-onnx components used by {@link SherpaSTTService}: Whisper offline ASR and Silero VAD.
 * <p>
 * Package-private: configuration details stay next to the service without exposing sherpa types publicly.
 * </p>
 */
final class SherpaOnnxConfigurator {

    private SherpaOnnxConfigurator() {
    }

    /**
     * Locates encoder, decoder, and token files under {@code modelPath} and builds a greedy-decoding offline
     * Whisper recognizer aligned with {@link ModelManager#STT_SAMPLE_RATE}.
     * Uses greedy search for speed (partials) or beam search for accuracy (finals).
     *
     * @param modelPath directory listing Whisper ONNX and token assets
     * @param language  Whisper language code (already normalized by the caller when applicable)
     * @param useBeamSearch if true, uses beam search (slower, more accurate); if false, uses greedy (faster)
     * @return a new recognizer; caller must {@link OfflineRecognizer#release()} when done
     * @throws IOException if required files are missing or {@code modelPath} is not readable
     */
    static OfflineRecognizer createWhisperRecognizer(Path modelPath, String language, boolean useBeamSearch) throws IOException {
        Path encoderPath = findFileBySuffix(modelPath, "-encoder.int8.onnx", "-encoder.onnx");
        Path decoderPath = findFileBySuffix(modelPath, "-decoder.int8.onnx", "-decoder.onnx");
        Path tokensPath = findFileBySuffix(modelPath, "-tokens.txt", "tokens.txt");

        if (encoderPath == null || decoderPath == null || tokensPath == null) {
            throw new IOException("Mandatory Whisper model files missing in: " + modelPath);
        }

        OfflineWhisperModelConfig whisperConfig = OfflineWhisperModelConfig.builder()
                .setEncoder(encoderPath.toString())
                .setDecoder(decoderPath.toString())
                .setLanguage(language)
                .setTask("transcribe")
                .build();

        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setWhisper(whisperConfig)
                .setTokens(tokensPath.toString())
                .setNumThreads(2)
                .build();

        String decodingMethod = useBeamSearch ? "beam_search" : "greedy_search";
        
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(ModelManager.STT_SAMPLE_RATE)
                        .setFeatureDim(80)
                        .build())
                .setDecodingMethod(decodingMethod)
                .setMaxActivePaths(useBeamSearch ? 4 : 1) // Beam size of 4 for beam search
                .build();

        return new OfflineRecognizer(config);
    }
    
    /**
     * Backward compatibility: creates a greedy-search recognizer.
     */
    static OfflineRecognizer createWhisperRecognizer(Path modelPath, String language) throws IOException {
        return createWhisperRecognizer(modelPath, language, false);
    }

    /**
     * Builds a Silero VAD instance using thresholds and durations from {@code modelManager}.
     *
     * @param modelManager supplies VAD path, readiness, and timing/threshold parameters
     * @return a new VAD; caller must {@link Vad#release()} when done
     * @throws IOException if the VAD file is not available
     */
    static Vad createSileroVad(ModelManager modelManager) throws IOException {
        Path vadPath = modelManager.getVADModelPath();
        if (!modelManager.isVADModelReady()) {
            throw new IOException("Silero VAD model not found. Please ensure ModelManager has downloaded it.");
        }

        SileroVadModelConfig sileroConfig = SileroVadModelConfig.builder()
                .setModel(vadPath.toString())
                .setThreshold(modelManager.getSTTVadThreshold())
                .setMinSilenceDuration(modelManager.getSTTMinSilenceDuration())
                .setMinSpeechDuration(modelManager.getSTTMinSpeechDuration())
                .setWindowSize(ModelManager.VAD_WINDOW_SIZE)
                .build();

        VadModelConfig config = VadModelConfig.builder()
                .setSileroVadModelConfig(sileroConfig)
                .setSampleRate(ModelManager.STT_SAMPLE_RATE)
                .setNumThreads(1)
                .build();

        return new Vad(config);
    }

    /**
     * Returns the first file in {@code directory} whose name ends with one of {@code suffixes}, trying suffixes in
     * order (e.g. prefer quantized {@code .int8.onnx}) when multiple patterns are listed first.
     *
     * @param directory model directory to scan
     * @param suffixes    candidate filename suffixes; order defines preference per iteration
     * @return matching path, or {@code null} if none match
     * @throws IOException if the directory cannot be listed
     */
    static Path findFileBySuffix(Path directory, String... suffixes) throws IOException {
        try (var stream = Files.list(directory)) {
            List<Path> files = stream.toList();
            for (String suffix : suffixes) {
                for (Path file : files) {
                    if (file.getFileName().toString().endsWith(suffix)) {
                        return file;
                    }
                }
            }
        }
        return null;
    }
}
