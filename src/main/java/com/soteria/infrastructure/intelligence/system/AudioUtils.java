package com.soteria.infrastructure.intelligence.system;

import javax.sound.sampled.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for resilient audio acquisition.
 * Handles common Java Sound API issues on Windows and Linux.
 */
public class AudioUtils {
    private static final Logger logger = Logger.getLogger(AudioUtils.class.getName());

    private AudioUtils() {}

    /**
     * Attempts to find and open a TargetDataLine with the requested format.
     * Tries multiple strategies to bypass common "Line not supported" errors.
     * Returns an ALREADY OPENED line.
     */
    public static TargetDataLine getResilientMic(AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        
        // Strategy 0: Retry loop for busy lines
        TargetDataLine defaultLine = tryOpenDefaultLine(info, format);
        if (defaultLine != null) return defaultLine;

        // Strategy 1: Targeted mixer search
        TargetDataLine mixerLine = searchInMixers(info, format);
        if (mixerLine != null) return mixerLine;

        // Strategy 2: Fallback to "Primary Sound Capture" or similar
        TargetDataLine namedLine = searchInNamedMixers(info, format);
        if (namedLine != null) return namedLine;

        throw new LineUnavailableException("No compatible microphone line found for " + format + 
            ". Please ensure a recording device is enabled in Windows Sound settings.");
    }

    private static TargetDataLine tryOpenDefaultLine(DataLine.Info info, AudioFormat format) throws LineUnavailableException {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                if (AudioSystem.isLineSupported(info)) {
                    TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(format);
                    logger.log(Level.INFO, "Acquired microphone on attempt {0}", (i + 1));
                    return line;
                }
            } catch (LineUnavailableException e) {
                if (i == maxRetries - 1) throw e;
                logger.log(Level.WARNING, "Microphone busy, retrying in 100ms... (Attempt {0})", (i + 1));
                try { Thread.sleep(100); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            } catch (Exception e) {
                logger.log(Level.FINE, "Default system microphone acquisition failed: {0}", e.getMessage());
            }
        }
        return null;
    }

    private static TargetDataLine searchInMixers(DataLine.Info info, AudioFormat format) {
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                    line.open(format);
                    logger.log(Level.INFO, "Using microphone from validated mixer: {0}", mixerInfo.getName());
                    return line;
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not open line from validated mixer {0}: {1}", 
                    new Object[]{mixerInfo.getName(), e.getMessage()});
            }
        }
        return null;
    }

    private static TargetDataLine searchInNamedMixers(DataLine.Info info, AudioFormat format) {
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = mixerInfo.getName().toLowerCase();
            if (name.contains("capture") || name.contains("microphone") || name.contains("primary")) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                    line.open(format);
                    logger.log(Level.INFO, "Using fallback named mixer: {0}", mixerInfo.getName());
                    return line;
                } catch (Exception _) {
                    // Ignore and keep searching
                }
            }
        }
        return null;
    }
}
