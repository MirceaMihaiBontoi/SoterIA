package com.soteria.infrastructure.sensor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort read of the device's own phone number.
 *
 * Windows: no public API to get a user phone number from a laptop SIM-less
 * device; we try WMI for modem-based devices and otherwise return UNKNOWN.
 *
 * Android (future): will read TelephonyManager.getLine1Number() once the
 * mobile bridge is wired up.
 *
 * All callers must treat the result as possibly UNKNOWN — the field exists
 * for display and as a hand-off to 112 operators; it is NOT required to
 * complete onboarding.
 */
public final class DevicePhoneDetector {

    public static final String UNKNOWN = "Unknown";

    private static final Logger log = Logger.getLogger(DevicePhoneDetector.class.getName());

    private DevicePhoneDetector() { }

    public static String detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("android")) {
            // Placeholder for native bridge (TelephonyManager#getLine1Number).
            return UNKNOWN;
        }
        if (os.contains("win")) {
            return detectWindows();
        }
        return UNKNOWN;
    }

    private static String detectWindows() {
        // Targets USB/integrated mobile broadband modems. Most desktops/laptops
        // won't have one, in which case the WMI query returns nothing and we
        // fall through to UNKNOWN.
        String psCommand =
                "$ErrorActionPreference='SilentlyContinue'; " +
                "$m = Get-CimInstance -Namespace root\\cimv2\\mdm -ClassName MDM_RemoteAccess_NumericAddress 2>$null; " +
                "if ($m) { $m | Select-Object -First 1 -ExpandProperty Address }";
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", psCommand);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor();
                if (line != null && !line.isBlank() && !line.equalsIgnoreCase(UNKNOWN)) {
                    return line.trim();
                }
            }
        } catch (InterruptedException e) {
            log.log(Level.FINE, "Windows phone detection interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.log(Level.FINE, "Windows phone detection failed (non-fatal)", e);
        }
        return UNKNOWN;
    }
}
