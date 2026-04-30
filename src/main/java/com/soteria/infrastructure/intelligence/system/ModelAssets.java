package com.soteria.infrastructure.intelligence.system;

/**
 * Constants and asset definitions for AI models used in the system.
 * This class centralizes all external URLs and internal file names to ensure consistency.
 */
public final class ModelAssets {
    private ModelAssets() {
        // Prevent instantiation
    }

    public static final String MODEL_DIR = "models";

    // STT Model — Lightweight Multilingual ASR (99 languages)
    public static final String STT_MODEL_NAME = "sherpa-onnx-whisper-small";
    public static final String STT_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2";

    // Brain (LLM) — GGUFs published by unsloth on HuggingFace.
    public static final String LLM_STABLE_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf";
    public static final String LLM_PRO_URL = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q8_0.gguf";

    // Triage (Intent) Model — Specialized crisis/emergency classifier (Local)
    public static final String TRIAGE_MODEL_NAME = "soteria-triage-v1.gguf";

    // TTS Model — Kokoro-82M for multilingual speech synthesis
    public static final String TTS_MODEL_NAME = "kokoro-multi-lang-v1_0";
    public static final String TTS_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2";

    // KWS Model — sherpa-onnx KeywordSpotter (3M parameters, Bilingual ZH/EN)
    public static final String KWS_MODEL_NAME = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20";
    public static final String KWS_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2";

    // VAD Model — Silero VAD for robust speech detection
    public static final String VAD_MODEL_NAME = "silero_vad.onnx";
    public static final String VAD_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    public static final String TAR_BZ2_EXT = ".tar.bz2";
    public static final String CLEANUP_ERROR_MSG = "Failed to delete tar.bz2 after extraction";

    // --- STT Configuration ---
    public static final int STT_SAMPLE_RATE = 16000;
    public static final int STT_CHANNELS = 1;
    public static final int STT_BIT_DEPTH = 16;
    public static final int VAD_WINDOW_SIZE = 512;
    
    // Configurable Parameters
    public static final float STT_VOLUME_BOOST = 1.0f;
    public static final float STT_VAD_THRESHOLD = 0.35f;
    public static final float STT_MIN_SILENCE_DURATION = 0.25f;
    public static final float STT_MIN_SPEECH_DURATION = 0.5f;
}
