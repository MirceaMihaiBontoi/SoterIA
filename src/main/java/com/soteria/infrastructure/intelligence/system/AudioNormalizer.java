package com.soteria.infrastructure.intelligence.system;

/**
 * Automatic Gain Control (AGC) and Stream Normalizer for real-time audio.
 * Inspired by professional audio pipelines to handle varying microphone gains.
 */
public class AudioNormalizer {
    private static final double TARGET_DBFS = -12.0;
    private static final double TARGET_RMS = 32767.0 * Math.pow(10.0, TARGET_DBFS / 20.0);
    private static final double MAX_GAIN = 32.0; // Extreme gain for near-silent inputs
    private static final double MIN_GAIN = 0.5;
    private static final double ATTACK_COEFF = 0.8;  // React almost instantly to loud sounds
    private static final double RELEASE_COEFF = 0.2; // Increase gain faster when speech starts
    private static final double SILENCE_RMS_THRESHOLD = 5.0; // Very sensitive to any signal

    private double smoothedGain = 1.0;

    /**
     * Normalizes the audio frame in-place or returns a new one.
     * @param frame The raw PCM16LE bytes
     * @param length The number of bytes to process
     */
    public void normalize(byte[] frame, int length) {
        if (frame == null || length < 2) return;

        // Ensure we only process full 16-bit samples
        int len = length & ~1;
        double rms = calculateRMS(frame, len);

        // Only adjust gain if we detect meaningful energy (not just silence/noise)
        if (rms > SILENCE_RMS_THRESHOLD) {
            double idealGain = TARGET_RMS / rms;
            idealGain = Math.min(idealGain, MAX_GAIN);
            idealGain = Math.max(idealGain, MIN_GAIN);

            // Use smoothed gain to prevent "popping"
            double coeff = (idealGain < smoothedGain) ? ATTACK_COEFF : RELEASE_COEFF;
            smoothedGain = smoothedGain * (1.0 - coeff) + idealGain * coeff;
        }

        // Apply gain to the buffer
        for (int i = 0; i < len; i += 2) {
            short sample = (short) ((frame[i] & 0xFF) | (frame[i + 1] << 8));
            long amplified = Math.round(sample * smoothedGain);

            // Hard clipping
            if (amplified > 32767) amplified = 32767;
            else if (amplified < -32768) amplified = -32768;

            frame[i] = (byte) (amplified & 0xFF);
            frame[i + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }

    private double calculateRMS(byte[] buffer, int length) {
        double sum = 0.0;
        int samples = length / 2;
        for (int i = 0; i < length; i += 2) {
            short val = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sum += (double) val * val;
        }
        return Math.sqrt(sum / samples);
    }

    public void reset() {
        this.smoothedGain = 1.0;
    }

    public double getCurrentGain() {
        return smoothedGain;
    }
}
