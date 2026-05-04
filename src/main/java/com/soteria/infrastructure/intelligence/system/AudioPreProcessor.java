package com.soteria.infrastructure.intelligence.system;

/**
 * Professional-grade audio preprocessing pipeline for speech recognition.
 * Implements techniques used by commercial STT systems: pre-emphasis, noise gating,
 * dynamic compression, and band-pass filtering.
 */
public class AudioPreProcessor {
    
    // Pre-emphasis filter coefficient (standard for speech: 0.95-0.97)
    private static final float PRE_EMPHASIS_COEFF = 0.95f;  // Más suave
    
    // Noise gate threshold in dBFS (silence below this level)
    private static final float NOISE_GATE_DB = -60.0f;  // Más permisivo
    private static final float NOISE_GATE_LINEAR = dbToLinear(NOISE_GATE_DB);
    
    // Dynamic compression parameters
    private static final float COMPRESSION_THRESHOLD_DB = -18.0f;  // Menos agresivo
    private static final float COMPRESSION_RATIO = 2.0f; // 2:1 compression (más suave)
    private static final float COMPRESSION_THRESHOLD_LINEAR = dbToLinear(COMPRESSION_THRESHOLD_DB);
    
    // Band-pass filter for human voice (300-3400 Hz @ 16kHz sample rate)
    // Simple IIR coefficients for computational efficiency
    private float prevInput = 0.0f;
    private float prevOutput = 0.0f;
    
    /**
     * Applies pre-emphasis filter to boost high frequencies where speech formants live.
     * This improves SNR for consonants and sibilants.
     *
     * @param samples input PCM samples (modified in-place)
     */
    public void applyPreEmphasis(float[] samples) {
        if (samples == null || samples.length == 0) return;
        
        // Process backwards to avoid needing a temp buffer
        for (int i = samples.length - 1; i > 0; i--) {
            samples[i] = samples[i] - PRE_EMPHASIS_COEFF * samples[i - 1];
        }
    }
    
    /**
     * Applies noise gate: silences audio below threshold to eliminate background hum.
     * Uses RMS measurement over the entire frame for stability.
     *
     * @param samples input PCM samples (modified in-place)
     */
    public void applyNoiseGate(float[] samples) {
        if (samples == null || samples.length == 0) return;
        
        float rms = calculateRMS(samples);
        
        if (rms < NOISE_GATE_LINEAR) {
            // Below threshold: silence the frame
            for (int i = 0; i < samples.length; i++) {
                samples[i] = 0.0f;
            }
        }
    }
    
    /**
     * Applies dynamic range compression to reduce loud peaks without losing quiet speech.
     * Uses soft-knee compression for natural sound.
     *
     * @param samples input PCM samples (modified in-place)
     */
    public void applyCompression(float[] samples) {
        if (samples == null || samples.length == 0) return;
        
        for (int i = 0; i < samples.length; i++) {
            float abs = Math.abs(samples[i]);
            
            if (abs > COMPRESSION_THRESHOLD_LINEAR) {
                // Apply compression above threshold
                float excess = abs - COMPRESSION_THRESHOLD_LINEAR;
                float compressed = COMPRESSION_THRESHOLD_LINEAR + (excess / COMPRESSION_RATIO);
                samples[i] = compressed * Math.signum(samples[i]);
            }
        }
    }
    
    /**
     * Applies simple high-pass filter to remove low-frequency rumble (< 300 Hz).
     * Uses first-order IIR for minimal CPU overhead.
     *
     * @param samples input PCM samples (modified in-place)
     */
    public void applyHighPassFilter(float[] samples) {
        if (samples == null || samples.length == 0) return;
        
        // Simple high-pass IIR: y[n] = 0.95 * (y[n-1] + x[n] - x[n-1])
        // Cutoff ~300 Hz @ 16kHz
        final float alpha = 0.95f;
        
        for (int i = 0; i < samples.length; i++) {
            float output = alpha * (prevOutput + samples[i] - prevInput);
            prevInput = samples[i];
            prevOutput = output;
            samples[i] = output;
        }
    }
    
    /**
     * Full preprocessing pipeline: applies all filters in optimal order.
     * Order matters: noise gate → compression → pre-emphasis → high-pass
     *
     * @param samples input PCM samples (modified in-place)
     */
    public void processFrame(float[] samples) {
        applyNoiseGate(samples);      // 1. Remove silence/noise first
        applyCompression(samples);     // 2. Compress dynamics
        applyHighPassFilter(samples);  // 3. Remove low-freq rumble
        applyPreEmphasis(samples);     // 4. Boost speech formants last
    }
    
    /**
     * Resets filter state (call when starting a new audio session).
     */
    public void reset() {
        prevInput = 0.0f;
        prevOutput = 0.0f;
    }
    
    /**
     * Calculates RMS (Root Mean Square) energy of a frame.
     */
    private float calculateRMS(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) {
            sum += sample * sample;
        }
        return (float) Math.sqrt(sum / samples.length);
    }
    
    /**
     * Converts dBFS to linear amplitude.
     */
    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }
    
    /**
     * Checks if frame contains voice-like energy (not just noise).
     * Uses simple spectral analysis: voice has energy in mid-frequencies.
     *
     * @param samples input PCM samples
     * @return true if likely contains speech
     */
    public boolean hasVoiceEnergy(float[] samples) {
        if (samples == null || samples.length == 0) return false;
        
        float rms = calculateRMS(samples);
        
        // Too quiet: definitely not speech
        if (rms < NOISE_GATE_LINEAR) {
            return false;
        }
        
        // Check zero-crossing rate (ZCR): speech has moderate ZCR
        // Noise has very high ZCR, silence has very low ZCR
        int zeroCrossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || 
                (samples[i] < 0 && samples[i - 1] >= 0)) {
                zeroCrossings++;
            }
        }
        
        float zcr = (float) zeroCrossings / samples.length;
        
        // Speech typically has ZCR between 0.05 and 0.25
        // (50-250 crossings per 1000 samples)
        return zcr >= 0.05f && zcr <= 0.25f;
    }
}
