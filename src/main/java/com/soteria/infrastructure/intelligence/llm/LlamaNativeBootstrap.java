package com.soteria.infrastructure.intelligence.llm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the {@code jllama} native built from <strong>SoterIA's tracked fork</strong> of java-llama.cpp
 * (see {@code lib/GEMMA4_JLLAMA.txt} for the canonical Git URL).
 *
 * <p>The Maven artifact {@code de.kherud:llama} supplies the Java API ({@code de.kherud.llama.*});
 * the <strong>native must match that fork's llama.cpp</strong> (build under {@code vendor/java-llama.cpp}
 * or copy binaries into {@code lib/}). If {@code lib/jllama.dll} (or {@code .dylib} / {@code .so}) exists,
 * this class sets {@value #LIB_PATH_PROPERTY} before any model loads. An explicit
 * {@code -Dde.kherud.llama.lib.path=...} is never overwritten.</p>
 */
public final class LlamaNativeBootstrap {

    /** System property read by {@code de.kherud.llama} JNI loaders (unchanged for binary compatibility). */
    public static final String LIB_PATH_PROPERTY = "de.kherud.llama.lib.path";

    private static final Logger log = Logger.getLogger(LlamaNativeBootstrap.class.getName());

    private LlamaNativeBootstrap() {}

    public static void applyIfNeeded() {
        String existing = System.getProperty(LIB_PATH_PROPERTY);
        if (existing != null && !existing.isBlank()) {
            return;
        }
        Path libDir = Path.of(System.getProperty("user.dir", "."), "lib").toAbsolutePath().normalize();
        if (!Files.isDirectory(libDir)) {
            return;
        }
        String nativeName = nativeLibraryFileName();
        if (!Files.isRegularFile(libDir.resolve(nativeName))) {
            return;
        }
        System.setProperty(LIB_PATH_PROPERTY, libDir.toString());
        log.log(Level.CONFIG, () -> "Using jllama native from " + libDir + " (fork build; see GEMMA4_JLLAMA.txt)");
    }

    private static String nativeLibraryFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "jllama.dll";
        }
        if (os.contains("mac")) {
            return "jllama.dylib";
        }
        return "jllama.so";
    }
}
