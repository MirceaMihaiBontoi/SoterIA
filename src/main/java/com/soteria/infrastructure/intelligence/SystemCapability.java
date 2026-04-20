package com.soteria.infrastructure.intelligence;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.logging.Logger;

/**
 * Detects system resources to determine the optimal AI model profile.
 */
public class SystemCapability {
    private static final Logger logger = Logger.getLogger(SystemCapability.class.getName());
    
    // Thresholds for choosing between models (GB)
    private static final long T1_THRESHOLD_GB = 3;
    private static final long T2_THRESHOLD_GB = 4;
    private static final long T3_THRESHOLD_GB = 6;
    private static final long T4_THRESHOLD_GB = 12;
    private static final long BYTES_IN_GB = 1024L * 1024L * 1024L;

    public enum AIModelProfile {
        ULTRA_LITE("gemma-3-1b-it-q4_k_m.gguf", 0.7),
        LITE("gemma-3-1b-it-q8_0.gguf", 1.2),
        BALANCED("gemma-3-4b-it-q4_k_m.gguf", 2.7),
        PERFORMANCE("gemma-3-4b-it-q4_k_m.gguf", 2.7),
        ULTRA("gemma-3-4b-it-q8_0.gguf", 4.3);

        private final String displayName;
        private final double sizeGB;

        AIModelProfile(String displayName, double sizeGB) {
            this.displayName = displayName;
            this.sizeGB = sizeGB;
        }

        public String getDisplayName() {
            return displayName;
        }

        public double getSizeGB() {
            return sizeGB;
        }
    }

    private final long totalMemory;
    private final int availableProcessors;
    private final AIModelProfile recommendedProfile;

    public SystemCapability() {
        this(((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize());
    }

    /**
     * Internal constructor for testing.
     */
    SystemCapability(long totalMemory) {
        this.totalMemory = totalMemory;
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
        
        long ramInGB = totalMemory / BYTES_IN_GB;

        if (ramInGB < T1_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.ULTRA_LITE;
        } else if (ramInGB < T2_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.LITE;
        } else if (ramInGB < T3_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.BALANCED;
        } else if (ramInGB < T4_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.PERFORMANCE;
        } else {
            this.recommendedProfile = AIModelProfile.ULTRA;
        }
        
        logger.info(() -> String.format("System Resources Detected: %d GB RAM, %d CPU cores. Recommended Profile: %s",
                ramInGB, availableProcessors, recommendedProfile));
    }

    public long getTotalMemory() {
        return totalMemory;
    }

    public int getAvailableProcessors() {
        return availableProcessors;
    }

    public AIModelProfile getRecommendedProfile() {
        return recommendedProfile;
    }
    
    public boolean isLowPowerDevice() {
        return recommendedProfile == AIModelProfile.ULTRA_LITE || recommendedProfile == AIModelProfile.LITE;
    }
}
