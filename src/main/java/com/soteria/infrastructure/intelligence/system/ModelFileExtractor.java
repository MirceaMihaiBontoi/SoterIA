package com.soteria.infrastructure.intelligence.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles extraction of TAR.BZ2 model bundles.
 */
public class ModelFileExtractor {
    private static final Logger logger = Logger.getLogger(ModelFileExtractor.class.getName());

    public void extractTarBz2(Path tarFile, Path destDir) {
        logger.log(Level.INFO, "Extracting: {0} (this may take a minute...)", tarFile.getFileName());
        try (InputStream fi = new java.io.BufferedInputStream(Files.newInputStream(tarFile), 128 * 1024);
             InputStream bi = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(fi);
             InputStream bbi = new java.io.BufferedInputStream(bi, 128 * 1024);
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream ti = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bbi)) {

            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                Path newPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(newPath), 128 * 1024)) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = ti.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }
            logger.log(Level.INFO, "Extraction complete: {0}", tarFile.getFileName());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to extract tar.bz2", e);
            throw new UncheckedIOException(e);
        }
    }
}
