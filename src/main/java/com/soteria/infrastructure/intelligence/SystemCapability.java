package com.soteria.infrastructure.intelligence;

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
    private static final long T1_THRESHOLD_GB = 3;
    private static final long T2_THRESHOLD_GB = 4;
    private static final long T4_THRESHOLD_GB = 12; // Back to 12GB for ultra (Q8) since threading is now optimized
    private static final long BYTES_IN_GB = 1024L * 1024L * 1024L;

    public enum AIModelProfile {
        ULTRA_LITE("gemma-3-1b-it-q4_k_m.gguf", 0.7),
        LITE("gemma-3-1b-it-q8_0.gguf", 1.2),
        BALANCED("gemma-3-4b-it-q4_k_m.gguf", 2.7),
        ULTRA("gemma-3-4b-it-q8_0.gguf", 4.6);

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
        } catch (Exception ignored) {
            // Standard OS bean method failed, fall through to alternatives
        }

        // 2. Linux/Android Fallback: Read /proc/meminfo
        try (RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r")) {
            String line = reader.readLine();
            if (line != null && line.contains("MemTotal:")) {
                String[] parts = line.split("\\s+");
                return Long.parseLong(parts[1]) * 1024L;
            }
        } catch (IOException ignored) {
            // /proc/meminfo not available or unreadable
        }

        // 3. Absolute Fallback: Use JVM Max Memory as a conservative hint
        return Runtime.getRuntime().maxMemory();
    }

    private static long tryGetMemory(java.lang.management.OperatingSystemMXBean osBean, String methodName) {
        try {
            var method = osBean.getClass().getMethod(methodName);
            return (Long) method.invoke(osBean);
        } catch (Exception e1) {
            try {
                var fallbackMethod = osBean.getClass().getMethod("getTotalMemorySize");
                return (Long) fallbackMethod.invoke(osBean);
            } catch (Exception e2) {
                return -1L;
            }
        }
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
        } else if (ramInGB < T4_THRESHOLD_GB) {
            this.recommendedProfile = AIModelProfile.BALANCED;
        } else {
            this.recommendedProfile = AIModelProfile.ULTRA; // Back to trusting RAM for ULTRA
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
            // Android/Tablet logic: big.LITTLE architecture and thermal constraints
            return Math.min(4, Math.max(1, availableProcessors / 2));
        }
        // Desktop logic: USAR SOLO NÚCLEOS FÍSICOS. 
        // Llama.cpp en CPU rinde MUCHO mejor sin Hyperthreading/SMT.
        // En una CPU de 12 hilos (Ryzen 5500), 6 hilos es el punto dulce.
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
        return recommendedProfile == AIModelProfile.ULTRA_LITE || recommendedProfile == AIModelProfile.LITE;
    }
}
