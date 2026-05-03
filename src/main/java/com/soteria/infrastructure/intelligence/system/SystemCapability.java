package com.soteria.infrastructure.intelligence.system;

import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.RandomAccessFile;
import java.io.IOException;

/**
 * Detects system resources to determine the optimal AI model profile.
 */
public class SystemCapability {
    private static final Logger logger = Logger.getLogger(SystemCapability.class.getName());

    // Thresholds for choosing between models (GB)
    /** Below this RAM: recommend the smallest bundled GGUF (LITE / Gemma 4 E2B). */
    private static final long LITE_RAM_THRESHOLD_GB = 6;
    private static final long E4B_Q8_THRESHOLD_GB = 12;
    private static final long BYTES_IN_GB = 1024L * 1024L * 1024L;

    /**
     * Resolves a profile id from persisted user data. Accepts legacy {@code E2B} (old enum name).
     *
     * @return matching profile, or {@code null} if unknown
     */
    public static AIModelProfile parseStoredProfile(String persistedName) {
        if (persistedName == null || persistedName.isBlank()) {
            return null;
        }
        try {
            return AIModelProfile.valueOf(persistedName);
        } catch (IllegalArgumentException _) {
            if ("E2B".equals(persistedName)) {
                return AIModelProfile.LITE;
            }
            if ("BALANCED".equalsIgnoreCase(persistedName)) {
                return AIModelProfile.STABLE;
            }
            return null;
        }
    }

    public enum AIModelProfile {
        /** Smallest Unsloth bundle: {@code gemma-4-E2B-it-GGUF}. */
        LITE("onboarding.model.profile.lite", 1.9),
        /** E4B Q4. */
        STABLE("onboarding.model.profile.stable", 3.2),
        /** E4B Q8. */
        EXPERT("onboarding.model.profile.expert", 6.0);

        private final String messageKey;
        private final double sizeGB;

        AIModelProfile(String messageKey, double sizeGB) {
            this.messageKey = messageKey;
            this.sizeGB = sizeGB;
        }

        /** Resource bundle key for localized UI labels (not user-facing text by itself). */
        public String getMessageKey() {
            return messageKey;
        }

        public double getSizeGB() {
            return sizeGB;
        }
    }

    private final long totalMemory;
    private final int availableProcessors;
    private final AIModelProfile recommendedProfile;

    public SystemCapability() {
        this(detectTotalRAM());
    }

    private static long detectTotalRAM() {
        // Safe cross-platform detection
        try {
            // 1. Try standard OperatingSystemMXBean (available on most OpenJDKs)
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

            // On desktop Oracle/OpenJDK, we can cast to get physical memory
            // But we use reflection or optional cast to be Android-safe
            if (osBean.getClass().getName().contains("OperatingSystemMXBean")) {
                return tryGetMemory(osBean, "getTotalPhysicalMemorySize");
            }
        } catch (Exception _) {
            // Standard OS bean method failed, fall through to alternatives
        }

        // 2. Linux/Android Fallback: Read /proc/meminfo
        try (RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r")) {
            String line = reader.readLine();
            if (line != null && line.contains("MemTotal:")) {
                String[] parts = line.split("\\s+");
                return Long.parseLong(parts[1]) * 1024L;
            }
        } catch (IOException _) {
            // /proc/meminfo not available or unreadable
        }

        // 3. Absolute Fallback: Use JVM Max Memory as a conservative hint
        return Runtime.getRuntime().maxMemory();
    }

    private static long tryGetMemory(java.lang.management.OperatingSystemMXBean osBean, String methodName) {
        try {
            var method = osBean.getClass().getMethod(methodName);
            return (Long) method.invoke(osBean);
        } catch (Exception _) {
            try {
                var fallbackMethod = osBean.getClass().getMethod("getTotalMemorySize");
                return (Long) fallbackMethod.invoke(osBean);
            } catch (Exception _) {
                return -1L;
            }
        }
    }

    /**
     * Internal constructor for testing.
     */
    public SystemCapability(long totalMemory) {
        this.totalMemory = totalMemory;
        this.availableProcessors = Runtime.getRuntime().availableProcessors();

        long ramInGB = totalMemory / BYTES_IN_GB;

        if (ramInGB < LITE_RAM_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.LITE;
        } else if (ramInGB < E4B_Q8_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.STABLE;
        } else {
            this.recommendedProfile = AIModelProfile.EXPERT;
        }

        logger.info("====================================================");
        logger.info("HARDWARE TELEMETRY REPORT:");
        logger.log(Level.INFO, () -> "- Detected CPU Cores (Logical): " + availableProcessors);
        logger.log(Level.INFO, () -> "- Total Physical RAM: " + ramInGB + " GB");
        logger.log(Level.INFO, () -> "- Low Power Mode: " + isLowPowerDevice());
        logger.log(Level.INFO, () -> "- Recommended Profile: " + recommendedProfile);
        logger.log(Level.INFO, () -> "- Target Inference Threads: " + getIdealThreadCount());
        logger.info("====================================================");
    }

    /**
     * Calculates the ideal number of threads for AI generation.
     * Prevents system lag by leaving at least one core free.
     * On mobile/low-power devices, caps threads to avoid overheating.
     */
    public int getIdealThreadCount() {
        if (isLowPowerDevice()) {
            // Android/tablet: big.LITTLE and thermal constraints
            return Math.clamp(availableProcessors / 2, 1, 4);
        }
        // Desktop: avoid using every logical CPU; llama.cpp on CPU often does better
        // without leaning on all SMT pairs (e.g. ~half of logical cores).
        return Math.max(1, availableProcessors / 2);
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
        long ramGb = totalMemory / BYTES_IN_GB;
        return recommendedProfile == AIModelProfile.LITE || ramGb < 4;
    }
}
