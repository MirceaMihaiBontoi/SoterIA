package com.soteria.infrastructure.intelligence.system;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized loader for native libraries used by sherpa-onnx.
 * Ensures libraries are loaded only once and handles fallbacks for Windows environments.
 */
public class NativeLibraryLoader {
    private static final Logger logger = Logger.getLogger(NativeLibraryLoader.class.getName());
    private static boolean loaded = false;

    private NativeLibraryLoader() {
        // Utility class
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        try {
            if (tryLoadFromSystem()) {
                loaded = true;
                return;
            }

            // Priority 2: Local project fallback (lib/native)
            String userDir = System.getProperty("user.dir");
            Path nativeDir = java.nio.file.Paths.get(userDir, "lib", "native");
            
            Path ortPath = nativeDir.resolve("onnxruntime.dll");
            Path jniPath = nativeDir.resolve("sherpa-onnx-jni.dll");

            if (java.nio.file.Files.exists(ortPath)) {
                System.load(ortPath.toAbsolutePath().toString());
                logger.log(Level.INFO, "Loaded onnxruntime from: {0}", ortPath);
            }
            
            if (java.nio.file.Files.exists(jniPath)) {
                System.load(jniPath.toAbsolutePath().toString());
                logger.log(Level.INFO, "Loaded sherpa-onnx-jni from: {0}", jniPath);
            }
            
            loaded = true;
        } catch (LinkageError | Exception t) {
            logger.log(Level.SEVERE, "CRITICAL: Failed to load native libraries for SoterIA. Audio services will fail.", t);
            throw new IllegalStateException("Native library loading failed: " + t.getMessage(), t);
        }
    }

    private static boolean tryLoadFromSystem() {
        try {
            System.loadLibrary("onnxruntime");
            System.loadLibrary("sherpa-onnx-jni");
            logger.info("Native libraries loaded from system path");
            return true;
        } catch (UnsatisfiedLinkError e) {
            logger.log(Level.FINE, "System path load failed, falling back to lib/native: {0}", e.getMessage());
            return false;
        }
    }
}
