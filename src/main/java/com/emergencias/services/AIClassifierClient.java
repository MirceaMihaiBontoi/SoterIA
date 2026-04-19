package com.emergencias.services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class AIClassifierClient implements IEmergencyClassifier {

    private static final Logger log = Logger.getLogger(AIClassifierClient.class.getName());

    private static final int    MAX_RETRIES             = 3;
    private static final long   BACKOFF_BASE_MS         = 500;
    private static final int    CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final Duration CIRCUIT_OPEN_TTL      = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public AIClassifierClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ── IEmergencyClassifier ──────────────────────────────────────────────────

    @Override
    public String classify(String text) {
        String body = "{\"text\": \"" + escapeJson(text) + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/classify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();
        return sendWithRetry(req);
    }

    @Override
    public boolean isAvailable() {
        if (circuitBreaker.isOpen()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                circuitBreaker.recordSuccess();
                return true;
            }
            circuitBreaker.recordFailure();
            return false;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return false;
        }
    }

    // ── Other endpoints ───────────────────────────────────────────────────────

    public String chat(String message, String context) {
        String body = "{\"message\": \"" + escapeJson(message) +
                      "\", \"context\": \"" + escapeJson(context) + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();
        return sendWithRetry(req);
    }

    public String geolocate() {
        if (circuitBreaker.isOpen()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/geolocate"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && !resp.body().contains("\"error\"")) return resp.body();
            return null;
        } catch (Exception e) {
            log.warning("Error geolocate: " + e.getMessage());
            return null;
        }
    }

    public byte[] synthesize(String text, String emotion) {
        if (circuitBreaker.isOpen()) return null;
        try {
            String body = "{\"text\": \"" + escapeJson(text) +
                          "\", \"emotion\": \"" + escapeJson(emotion) + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/tts"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                String ct = resp.headers().firstValue("content-type").orElse("");
                if (ct.contains("application/json")) {
                    log.warning("TTS devolvió JSON en lugar de WAV: " +
                            new String(resp.body(), StandardCharsets.UTF_8));
                    return null;
                }
                return resp.body();
            }
            log.warning("TTS HTTP " + resp.statusCode());
            return null;
        } catch (Exception e) {
            log.warning("Error TTS: " + e.getMessage());
            return null;
        }
    }

    public String transcribeAdvanced(byte[] audioData, int sampleRate) {
        if (circuitBreaker.isOpen()) return null;
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] body = buildMultipart(boundary, audioData, sampleRate);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/stt"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            log.warning("Error STT: " + e.getMessage());
            return null;
        }
    }

    public String analyzeEmotion(byte[] audioData, int sampleRate) {
        if (circuitBreaker.isOpen()) return null;
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] body = buildMultipart(boundary, audioData, sampleRate);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/analyze-emotion"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            log.warning("Error analyzeEmotion: " + e.getMessage());
            return null;
        }
    }

    public String getSystemInfo() {
        if (circuitBreaker.isOpen()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/system-info"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Retry + circuit breaker ───────────────────────────────────────────────

    private String sendWithRetry(HttpRequest request) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (circuitBreaker.isOpen()) {
                log.warning("Circuit breaker abierto — saltando llamada al backend");
                return null;
            }
            try {
                HttpResponse<String> resp = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (resp.statusCode() == 200) {
                    circuitBreaker.recordSuccess();
                    return resp.body();
                }
                if (resp.statusCode() >= 400 && resp.statusCode() < 500) {
                    // Error del cliente — no reintentar
                    log.warning("HTTP " + resp.statusCode() + " (4xx) — no se reintenta");
                    return null;
                }
                // 5xx — transitorio, reintentar
                log.warning("HTTP " + resp.statusCode() + " (5xx), intento " + (attempt + 1) + "/" + MAX_RETRIES);
                circuitBreaker.recordFailure();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException e) {
                log.warning("IOException al contactar backend: " + e.getMessage() +
                            " — intento " + (attempt + 1) + "/" + MAX_RETRIES);
                circuitBreaker.recordFailure();
            } catch (Exception e) {
                log.warning("Error inesperado: " + e.getMessage());
                circuitBreaker.recordFailure();
            }

            if (attempt < MAX_RETRIES - 1) {
                try {
                    Thread.sleep(BACKOFF_BASE_MS * (1L << attempt)); // 500ms, 1000ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    // ── Circuit breaker ───────────────────────────────────────────────────────

    private class CircuitBreaker {
        private int consecutiveFailures = 0;
        private Instant openedAt = Instant.EPOCH;
        private boolean open = false;

        synchronized boolean isOpen() {
            if (!open) return false;
            if (Duration.between(openedAt, Instant.now()).compareTo(CIRCUIT_OPEN_TTL) > 0) {
                open = false;
                consecutiveFailures = 0;
            }
            return open;
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            open = false;
        }

        synchronized void recordFailure() {
            consecutiveFailures++;
            if (consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
                open = true;
                openedAt = Instant.now();
                log.warning("Circuit breaker ABIERTO tras " + consecutiveFailures + " fallos consecutivos");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] buildMultipart(String boundary, byte[] audioData, int sampleRate) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n".getBytes());
        baos.write("Content-Type: audio/wav\r\n\r\n".getBytes());
        baos.write(audioData);
        baos.write("\r\n".getBytes());
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write("Content-Disposition: form-data; name=\"sample_rate\"\r\n\r\n".getBytes());
        baos.write(String.valueOf(sampleRate).getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n".getBytes());
        baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    // ── Static JSON extract helpers (sin cambios) ─────────────────────────────

    public static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    public static int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return -1;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    public static double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return -1;
        start += search.length();
        int end = start;
        while (end < json.length() &&
               (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        return Double.parseDouble(json.substring(start, end));
    }

    public static String[] extractStringArray(String json, String key) {
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start == -1) return new String[0];
        start += search.length();
        int end = json.indexOf("]", start);
        String content = json.substring(start, end).trim();
        if (content.isEmpty()) return new String[0];

        List<String> items = new ArrayList<>();
        boolean inQuotes = false;
        int itemStart = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && !inQuotes) { inQuotes = true; itemStart = i + 1; }
            else if (c == '"' && inQuotes) { inQuotes = false; items.add(content.substring(itemStart, i)); }
        }
        return items.toArray(new String[0]);
    }

    public static String[] extractEmergencies(String json) {
        String search = "\"emergencies\":[";
        int start = json.indexOf(search);
        if (start == -1) return new String[0];
        start += search.length();

        List<String> objects = new ArrayList<>();
        int depth = 0, objStart = -1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth == 0) objStart = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && objStart != -1) { objects.add(json.substring(objStart, i + 1)); objStart = -1; } }
            else if (c == ']' && depth == 0) break;
        }
        return objects.toArray(new String[0]);
    }
}
