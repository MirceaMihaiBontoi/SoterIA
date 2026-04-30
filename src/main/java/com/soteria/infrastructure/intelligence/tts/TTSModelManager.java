package com.soteria.infrastructure.intelligence.tts;

import com.k2fsa.sherpa.onnx.*;
import com.soteria.infrastructure.intelligence.system.NativeLibraryLoader;
import com.soteria.infrastructure.intelligence.system.LanguageUtils;

import java.nio.file.Path;

/**
 * Manages the lifecycle and configuration of the Sherpa-ONNX TTS model.
 * Handles native library loading and audio synthesis.
 */
public class TTSModelManager implements AutoCloseable {
    private final Path modelPath;
    private final TTSLogger ttsLogger;
    private OfflineTts offlineTts;

    public TTSModelManager(Path modelPath, TTSLogger ttsLogger, String language) {
        this.modelPath = modelPath;
        this.ttsLogger = ttsLogger;
        initialize(language);
    }

    private void initialize(String language) {
        try {
            NativeLibraryLoader.load();
            
            String lexiconPath = modelPath.resolve("lexicon-zh.txt") + "," + modelPath.resolve("lexicon-us-en.txt");
            String fstPath = modelPath.resolve("phone-zh.fst") + "," + modelPath.resolve("date-zh.fst") + ","
                    + modelPath.resolve("number-zh.fst");

            OfflineTtsKokoroModelConfig kokoroConfig = OfflineTtsKokoroModelConfig.builder()
                    .setModel(modelPath.resolve("model.onnx").toString())
                    .setVoices(modelPath.resolve("voices.bin").toString())
                    .setTokens(modelPath.resolve("tokens.txt").toString())
                    .setDataDir(modelPath.resolve("espeak-ng-data").toString())
                    .setDictDir(modelPath.resolve("dict").toString())
                    .setLexicon(lexiconPath)
                    .setLang(resolveLanguageCode(language))
                    .build();

            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                    .setKokoro(kokoroConfig)
                    .setNumThreads(4)
                    .setDebug(false)
                    .build();

            OfflineTtsConfig config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setRuleFsts(fstPath)
                    .setMaxNumSentences(1)
                    .build();

            this.offlineTts = new OfflineTts(config);
            ttsLogger.logInitialization(modelPath, 4);
        } catch (Exception e) {
            ttsLogger.error("Failed to initialize Sherpa-ONNX model", e);
            throw new IllegalStateException("TTS Model initialization failed", e);
        }
    }

    public GeneratedAudio generate(String text, int speakerId, float rate) {
        try {
            return offlineTts.generate(text, speakerId, rate);
        } catch (Exception e) {
            ttsLogger.error("Error generating audio for text: " + text, e);
            return null;
        }
    }

    public String resolveLanguageCode(String lang) {
        String code = LanguageUtils.isoCode(lang);
        return code.isEmpty() ? "en" : code;
    }

    @Override
    public void close() {
        if (offlineTts != null) {
            offlineTts.release();
            ttsLogger.info("TTS Model released");
        }
    }
}
