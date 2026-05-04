package com.soteria.infrastructure.intelligence.tts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TTSAudioPlayerTest {

    private TTSLogger mockLogger;
    private TTSAudioPlayer audioPlayer;

    @BeforeEach
    void setUp() {
        mockLogger = new TTSLogger();
        audioPlayer = new TTSAudioPlayer(mockLogger);
    }

    @Test
    @DisplayName("Should convert float samples to PCM16 little-endian")
    void floatToPcm16Conversion() {
        float[] samples = {0.0f, 0.5f, -0.5f, 1.0f, -1.0f};
        byte[] pcm = audioPlayer.floatToPcm16(samples);

        assertEquals(samples.length * 2, pcm.length);

        // Check 0.0f -> 0
        assertEquals(0, (short) ((pcm[1] << 8) | (pcm[0] & 0xFF)));

        // Check 1.0f -> 32767
        int idx1 = 6; // 4th sample
        short val1 = (short) ((pcm[idx1 + 1] << 8) | (pcm[idx1] & 0xFF));
        assertEquals(32767, val1);

        // Check -1.0f -> -32767
        int idx2 = 8; // 5th sample
        short val2 = (short) ((pcm[idx2 + 1] << 8) | (pcm[idx2] & 0xFF));
        assertEquals(-32767, val2);
    }

    @Test
    @DisplayName("Should clamp values above 1.0 and below -1.0")
    void clampsOutOfRangeValues() {
        float[] samples = {2.0f, -2.0f};
        byte[] pcm = audioPlayer.floatToPcm16(samples);

        // 2.0f should clamp to 1.0f -> 32767
        short val1 = (short) ((pcm[1] << 8) | (pcm[0] & 0xFF));
        assertEquals(32767, val1);

        // -2.0f should clamp to -1.0f -> -32767
        short val2 = (short) ((pcm[3] << 8) | (pcm[2] & 0xFF));
        assertEquals(-32767, val2);
    }

    @Test
    @DisplayName("Should apply volume scaling during PCM conversion")
    void appliesVolumeScaling() {
        audioPlayer.setVolume(0.5f);
        float[] samples = {1.0f};
        byte[] pcm = audioPlayer.floatToPcm16(samples);

        short val = (short) ((pcm[1] << 8) | (pcm[0] & 0xFF));
        // 1.0f * 0.5 * 32767 ≈ 16383
        assertTrue(Math.abs(val - 16383) < 10);
    }

    @Test
    @DisplayName("Should apply fade-in to PCM samples")
    void appliesFadeIn() {
        // Create PCM with constant amplitude
        byte[] pcm = new byte[240]; // 120 samples at 24000Hz = 5ms
        short constantValue = 10000;
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = (byte) (constantValue & 0xFF);
            pcm[i + 1] = (byte) ((constantValue >>> 8) & 0xFF);
        }

        audioPlayer.applyFadeIn(pcm);

        // First sample should be near zero
        short firstSample = (short) ((pcm[1] << 8) | (pcm[0] & 0xFF));
        assertTrue(Math.abs(firstSample) < 100);

        // Last sample should be close to original
        short lastSample = (short) ((pcm[pcm.length - 1] << 8) | (pcm[pcm.length - 2] & 0xFF));
        assertTrue(Math.abs(lastSample - constantValue) < 500);
    }

    @Test
    @DisplayName("Should apply fade-out to PCM samples")
    void appliesFadeOut() {
        // Create PCM with constant amplitude
        byte[] pcm = new byte[240]; // 120 samples
        short constantValue = 10000;
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = (byte) (constantValue & 0xFF);
            pcm[i + 1] = (byte) ((constantValue >>> 8) & 0xFF);
        }

        audioPlayer.applyFadeOut(pcm);

        // First sample should remain close to original
        short firstSample = (short) ((pcm[1] << 8) | (pcm[0] & 0xFF));
        assertTrue(Math.abs(firstSample - constantValue) < 500);

        // Last sample should be near zero
        short lastSample = (short) ((pcm[pcm.length - 1] << 8) | (pcm[pcm.length - 2] & 0xFF));
        assertTrue(Math.abs(lastSample) < 100);
    }

    @Test
    @DisplayName("Should generate silence with correct length")
    void generatesSilence() {
        int ms = 100;
        byte[] silence = audioPlayer.generateSilence(ms);

        // 24000 Hz * 0.1s * 2 bytes = 4800 bytes
        assertEquals(4800, silence.length);

        // All bytes should be zero
        for (byte b : silence) {
            assertEquals(0, b);
        }
    }

    @Test
    @DisplayName("Should generate silence for various durations")
    void generatesSilenceVariousDurations() {
        assertEquals(4800, audioPlayer.generateSilence(100).length); // 100ms
        assertEquals(7200, audioPlayer.generateSilence(150).length); // 150ms
        assertEquals(2880, audioPlayer.generateSilence(60).length);  // 60ms
        assertEquals(1440, audioPlayer.generateSilence(30).length);  // 30ms
    }

    @Test
    @DisplayName("Should report queue as empty initially")
    void queueEmptyInitially() {
        assertTrue(audioPlayer.isQueueEmpty());
    }

    @Test
    @DisplayName("Should handle volume range 0.0 to 1.0")
    void volumeRange() {
        audioPlayer.setVolume(0.0f);
        float[] samples = {1.0f};
        byte[] pcm = audioPlayer.floatToPcm16(samples);
        short val = (short) ((pcm[1] << 8) | (pcm[0] & 0xFF));
        assertEquals(0, val);

        audioPlayer.setVolume(1.0f);
        pcm = audioPlayer.floatToPcm16(samples);
        val = (short) ((pcm[1] << 8) | (pcm[0] & 0xFF));
        assertEquals(32767, val);
    }

    @Test
    @DisplayName("Should handle empty PCM array")
    void handlesEmptyPcm() {
        byte[] empty = audioPlayer.floatToPcm16(new float[0]);
        assertEquals(0, empty.length);
    }

    @Test
    @DisplayName("Should handle single sample")
    void handlesSingleSample() {
        float[] samples = {0.5f};
        byte[] pcm = audioPlayer.floatToPcm16(samples);
        assertEquals(2, pcm.length);
    }

    @Test
    @DisplayName("Fade-in should handle short PCM arrays")
    void fadeInHandlesShortArrays() {
        byte[] pcm = new byte[4]; // Only 2 samples
        pcm[0] = (byte) 0xFF;
        pcm[1] = (byte) 0x7F; // 32767
        pcm[2] = (byte) 0xFF;
        pcm[3] = (byte) 0x7F;

        assertDoesNotThrow(() -> audioPlayer.applyFadeIn(pcm));
    }

    @Test
    @DisplayName("Fade-out should handle short PCM arrays")
    void fadeOutHandlesShortArrays() {
        byte[] pcm = new byte[4]; // Only 2 samples
        pcm[0] = (byte) 0xFF;
        pcm[1] = (byte) 0x7F;
        pcm[2] = (byte) 0xFF;
        pcm[3] = (byte) 0x7F;

        assertDoesNotThrow(() -> audioPlayer.applyFadeOut(pcm));
    }
}
