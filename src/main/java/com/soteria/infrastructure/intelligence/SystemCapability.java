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
        ULTRA_LITE,  // For < 3GB RAM (Qwen3-0.6B Q4)
        LITE,        // For 4GB RAM (Gemma 4 E2B Q4)
        BALANCED,    // For 6GB RAM (Gemma 4 E2B Q8)
        PERFORMANCE, // For 8GB RAM (Gemma 4 E4B Q4)
        ULTRA        // For 12GB+ RAM (Gemma 4 E4B Q8)
    }

    private final long totalMemory;
    private final int availableProcessors;
    private final AIModelProfile recommendedProfile;

    public SystemCapability() {
        OperatingSystemMXBean osmxb = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.totalMemory = osmxb.getTotalMemorySize();
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
