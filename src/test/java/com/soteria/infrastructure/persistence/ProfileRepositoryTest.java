package com.soteria.infrastructure.persistence;

import com.soteria.core.model.UserData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProfileRepositoryTest {

    @TempDir
    Path tempDir;

    private ProfileRepository repository;

    @BeforeEach
    void setUp() {
        Path profilePath = tempDir.resolve("profile.json");
        repository = new ProfileRepository(profilePath);
    }

    @Test
    @DisplayName("Should correctly identify if the file exists")
    void existsIdentifiesFilePresence() throws IOException {
        assertFalse(repository.exists());
        repository.save(sample());
        assertTrue(repository.exists());
    }

    @Test
    @DisplayName("Should save and retrieve data without information loss")
    void saveAndLoadPreservesData() throws IOException {
        UserData original = sample();
        repository.save(original);

        Optional<UserData> loaded = repository.load();
        assertTrue(loaded.isPresent());
        assertEquals(original, loaded.get());
    }

    @Test
    @DisplayName("Load should return Optional.empty if the file does not exist")
    void loadReturnsEmptyOnMissingFile() {
        Optional<UserData> loaded = repository.load();
        assertTrue(loaded.isEmpty());
    }

    private UserData sample() {
        return new UserData(
            "Juan Perez", 
            "+34 600 000 000", 
            "Male", 
            "1985-05-20", 
            "Diabetic", 
            "Maria 611 222 333", 
            "STABLE", 
            "Spanish", 
            1.44f,
            true
        );
    }
}
