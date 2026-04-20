package com.soteria.infrastructure.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soteria.core.model.UserData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the single device-owner profile as JSON at ~/.soteria/profile.json.
 * SoterIA is a single-user app: there is no authentication — the device
 * owner is the user. First launch collects the profile; every subsequent
 * launch skips straight to the chat.
 */
public class ProfileRepository {
    private static final Logger log = Logger.getLogger(ProfileRepository.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path profileFile;

    public ProfileRepository() {
        this(Paths.get(System.getProperty("user.home"), ".soteria", "profile.json"));
    }

    public ProfileRepository(Path profileFile) {
        this.profileFile = profileFile;
    }

    public boolean exists() {
        return Files.exists(profileFile);
    }

    public Optional<UserData> load() {
        if (!exists()) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(profileFile.toFile(), UserData.class));
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to read profile.json — treating as missing", e);
            return Optional.empty();
        }
    }

    public void save(UserData profile) throws IOException {
        Files.createDirectories(profileFile.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(profileFile.toFile(), profile);
        log.log(Level.INFO, "Profile saved to {0}", profileFile);
    }
}
