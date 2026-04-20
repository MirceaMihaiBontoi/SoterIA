package com.soteria.infrastructure.intelligence;

import com.soteria.core.interfaces.EmergencyClassifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of EmergencyClassifier that connects to a remote AI service.
 * Includes circuit breaking and retry logic for robustness.
 */
public class RemoteIntelligenceService implements EmergencyClassifier {

    private static final Logger log = Logger.getLogger(RemoteIntelligenceService.class.getName());

    private static final int    MAX_RETRIES             = 3;
    private static final long   BACKOFF_BASE_MS         = 500;
    private static final int    CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final Duration CIRCUIT_OPEN_TTL      = Duration.ofSeconds(60);

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON   = "application/json";
    private static final String SIGNAL_STOP_RETRY   = "___STOP_RETRYING___";
    private static final byte[] EMPTY_BYTE_ARRAY    = new byte[0];

    private final String baseUrl;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public RemoteIntelligenceService(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // -- EmergencyClassifier Implementation ------------------------------------

    @Override
    public String classify(String text) {
        String body = "{\"text\": \"" + escapeJson(text) + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/classify"))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
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
        } catch (IOException | InterruptedException e) {
            circuitBreaker.recordFailure();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return false;
        }
    }

    // -- Extended Intelligence Features ---------------------------------------

    public String chat(String message, String context) {
        String body = "{\"message\": \"" + escapeJson(message) +
                      "\", \"context\": \"" + escapeJson(context) + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat"))
                .header(HEADER_CONTENT_TYPE, "application/json; charset=utf-8")
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
        } catch (IOException | InterruptedException e) {
            log.log(Level.WARNING, "Geolocate communication error: {0}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.log(Level.WARNING, "Geolocate error: {0}", e.getMessage());
            return null;
        }
    }

    public byte[] synthesize(String text, String emotion) {
        if (circuitBreaker.isOpen()) return EMPTY_BYTE_ARRAY;
        try {
            String body = "{\"text\": \"" + escapeJson(text) +
                          "\", \"emotion\": \"" + escapeJson(emotion) + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/tts"))
                    .header(HEADER_CONTENT_TYPE, "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                String ct = resp.headers().firstValue("content-type").orElse("");
                if (ct.contains(CONTENT_TYPE_JSON)) {
                    if (log.isLoggable(Level.WARNING)) {
                        log.log(Level.WARNING, "TTS returned JSON instead of WAV: {0}",
                                new String(resp.body(), StandardCharsets.UTF_8));
                    }
                    return EMPTY_BYTE_ARRAY;
                }
                return resp.body();
            }
            log.log(Level.WARNING, "TTS HTTP {0}", resp.statusCode());
            return EMPTY_BYTE_ARRAY;
        } catch (IOException | InterruptedException e) {
            log.log(Level.WARNING, "TTS communication error: {0}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return EMPTY_BYTE_ARRAY;
        } catch (Exception e) {
            log.log(Level.WARNING, "TTS Error: {0}", e.getMessage());
            return EMPTY_BYTE_ARRAY;
        }
    }

    // -- Retry + Circuit Breaker Logic -----------------------------------------

    private String sendWithRetry(HttpRequest request) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (circuitBreaker.isOpen()) {
                log.warning("Circuit breaker OPEN - skipping backend call");
                return null;
            }

            String result = tryProcessAttempt(request, attempt);
            if (result != null) {
                return SIGNAL_STOP_RETRY.equals(result) ? null : result;
            }

            if (attempt < MAX_RETRIES - 1 && !waitForBackoff(attempt)) {
                return null;
            }
        }
        return null;
    }

    private String tryProcessAttempt(HttpRequest request, int attempt) {
        try {
            return executeAttempt(request, attempt);
        } catch (IOException | InterruptedException e) {
            return handleRetryException(e, attempt) ? SIGNAL_STOP_RETRY : null;
        }
    }

    private boolean handleRetryException(Exception e, int attempt) {
        if (e instanceof InterruptedException) {
            log.warning("Request interrupted during retry");
            Thread.currentThread().interrupt();
            return true;
        }
        log.log(Level.WARNING, "IOException contacting backend: {0} - attempt {1}/{2}",
                new Object[]{e.getMessage(), attempt + 1, MAX_RETRIES});
        circuitBreaker.recordFailure();
        return false;
    }

    private String executeAttempt(HttpRequest request, int attempt) throws IOException, InterruptedException {
        HttpResponse<String> resp = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int code = resp.statusCode();
        if (code == 200) {
            circuitBreaker.recordSuccess();
            return resp.body();
        }
        if (code >= 400 && code < 500) {
            log.log(Level.WARNING, "HTTP {0} (4xx) - no retry", code);
            return SIGNAL_STOP_RETRY;
        }
        log.log(Level.WARNING, "HTTP {0} (5xx), attempt {1}/{2}",
                new Object[]{code, attempt + 1, MAX_RETRIES});
        circuitBreaker.recordFailure();
        return null;
    }

    private boolean waitForBackoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MS * (1L << attempt));
            return true;
        } catch (InterruptedException ie) {
            log.warning("Backoff interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

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
                log.log(Level.WARNING, "Circuit breaker OPENED after {0} consecutive failures", consecutiveFailures);
            }
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    // Static extractors for JSON handling (manual to avoid dependencies)
    public static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
