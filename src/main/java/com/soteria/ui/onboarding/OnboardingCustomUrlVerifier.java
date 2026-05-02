package com.soteria.ui.onboarding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Syntactic checks and a short HTTP probe for optional custom GGUF model URLs during onboarding.
 * <p>
 * Probe methods MUST run off the JavaFX application thread.
 * </p>
 */
final class OnboardingCustomUrlVerifier {

    private static final Logger log = Logger.getLogger(OnboardingCustomUrlVerifier.class.getName());
    private static final int MAX_URL_LENGTH = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private OnboardingCustomUrlVerifier() {
    }

    /**
     * Rejects obviously invalid inputs before network I/O.
     *
     * @param url non-empty trimmed URL from the user
     * @return user-facing error message, or {@code null} if syntax is acceptable
     */
    static String validateSyntax(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            return "Custom URL is too long.";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            return "Custom URL must use https://.";
        }
        if (!lower.endsWith(".gguf")) {
            return "Custom URL must point to a .gguf file.";
        }
        try {
            URI.create(url).toURL();
        } catch (IllegalArgumentException | java.net.MalformedURLException _) {
            return "Custom URL is malformed.";
        }
        return null;
    }

    /**
     * Confirms the URL responds with HEAD (or Range GET on HTTP 405 from some CDNs).
     *
     * @param url syntactically valid HTTPS GGUF URL
     * @return {@code null} on success, otherwise a message suitable for a UI label
     */
    static String probe(String url) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "SoterIA/1.0 (url-probe)")
                    .header("Accept", "*/*")
                    .build();
            HttpResponse<Void> resp = client.send(head, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return null;
            }
            if (code == 405) {
                return probeWithRange(client, url);
            }
            return "Server responded with HTTP " + code + " — check the URL.";
        } catch (HttpTimeoutException _) {
            return "The server took too long to respond. Try again.";
        } catch (java.net.ConnectException _) {
            return "Could not reach the server. Check your internet connection.";
        } catch (java.net.UnknownHostException _) {
            return "Unknown host. Check the URL spelling.";
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "URL verification was interrupted.";
        } catch (Exception e) {
            log.log(Level.FINE, "URL probe failed", e);
            return "Could not verify URL: " + e.getMessage();
        }
    }

    /**
     * Hugging Face CDNs sometimes return 405 for HEAD; mirrors the downloader pattern with a 1-byte Range GET.
     */
    private static String probeWithRange(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest get = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "SoterIA/1.0 (url-probe)")
                .header("Range", "bytes=0-0")
                .GET()
                .build();
        HttpResponse<Void> resp = client.send(get, HttpResponse.BodyHandlers.discarding());
        int code = resp.statusCode();
        if (code == 200 || code == 206) {
            return null;
        }
        return "Server responded with HTTP " + code + " — check the URL.";
    }
}
