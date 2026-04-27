package com.soteria.infrastructure.notification;

import com.soteria.core.model.EmergencyEvent;
import com.soteria.core.model.UserData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NotificationAlertService Tests")
class NotificationAlertServiceTest {

    @TempDir
    Path tempDir;

    private static final String TEST_LOG = "test.log";

    @Test
    @DisplayName("Should persist alert message to log file")
    void shouldPersistAlert() throws IOException {
        Path logFile = tempDir.resolve("alerts.log");
        NotificationAlertService service = new NotificationAlertService(logFile);
        
        UserData user = new UserData("Juan Perez", "+34600000000", "Male", "1980-01-01", "Allergies", "Maria +34611111111", "BALANCED", "Spanish", "");
        EmergencyEvent event = new EmergencyEvent(
                "Cardiac Arrest", "Madrid, Center", 9, LocalDateTime.now(), user.toString());

        boolean success = service.send(event);

        assertTrue(success, "Send should return true despite simulation");
        assertTrue(Files.exists(logFile), "Log file should be created");
        
        String content = Files.readString(logFile);
        assertTrue(content.contains("Type: Cardiac Arrest"));
        assertTrue(content.contains("Severity: 9/10"));
        assertTrue(content.contains("Juan Perez"));
        assertTrue(content.contains("User: "));
    }

    @Test
    @DisplayName("Should handle null events gracefully")
    void shouldHandleNullEvent() {
        NotificationAlertService service = new NotificationAlertService(tempDir.resolve(TEST_LOG));
        assertFalse(service.send(null), "Sending null should return false");
    }

    @Test
    @DisplayName("Should report alert type correctly")
    void shouldReportType() {
        NotificationAlertService service = new NotificationAlertService(tempDir.resolve(TEST_LOG));
        assertNotNull(service.getAlertType());
        assertTrue(service.getAlertType().contains("Simulated"));
    }

    @Test
    @DisplayName("notifyContacts should not crash with missing data")
    void notifyContactsSafety() {
        NotificationAlertService service = new NotificationAlertService(tempDir.resolve(TEST_LOG));
        // Should handle null/empty without exceptions
        assertDoesNotThrow(() -> service.notifyContacts(null, null));
        
        UserData emptyUser = new UserData("", "", "", "", "", "", "", "", "");
        assertDoesNotThrow(() -> service.notifyContacts(emptyUser, null));
    }

    @Test
    @DisplayName("Should handle multilingual data correctly (UTF-8)")
    void multilingualPersistence() throws IOException {
        Path logFile = tempDir.resolve("multilingual.log");
        NotificationAlertService service = new NotificationAlertService(logFile);
        
        // Test cases: [Language, Type, Location, Name]
        String[][] cases = {
            {"Chinese", "心脏骤停", "北京市中心", "王小明"},
            {"Arabic", "نوبة قلبية", "وسط القاهرة", "أحمد"},
            {"Hindi", "हृदय गति रुकना", "मुंबई", "राहुल"},
            {"Russian", "Остановка сердца", "Москва", "Иван"},
            {"Greek", "Καρδιακή ανακοπή", "Αθήνα", "Νίκος"}
        };

        for (String[] c : cases) {
            String type = c[1];
            String loc = c[2];
            String name = c[3];

            EmergencyEvent event = new EmergencyEvent(type, loc, 10, LocalDateTime.now(), "User: " + name);
            service.send(event);
            
            String content = Files.readString(logFile);
            assertTrue(content.contains(type), "Missing " + c[0] + " type");
            assertTrue(content.contains(loc), "Missing " + c[0] + " location");
            assertTrue(content.contains(name), "Missing " + c[0] + " name");
        }
    }
}
