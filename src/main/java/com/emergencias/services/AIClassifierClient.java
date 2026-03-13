package com.emergencias.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP que se comunica con el backend Python de clasificacion de emergencias.
 */
public class AIClassifierClient {
    private final String baseUrl;
    private final HttpClient client;

    public AIClassifierClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Envia texto al backend y devuelve la respuesta JSON cruda.
     * Retorna null si hay error de conexion.
     */
    public String classify(String text) {
        try {
            String jsonBody = "{\"text\": \"" + escapeJson(text) + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/classify"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            }
            System.err.println("Error del servidor: HTTP " + response.statusCode());
            return null;
        } catch (Exception e) {
            System.err.println("No se pudo conectar con el backend de IA: " + e.getMessage());
            return null;
        }
    }

    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

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
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    public static double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return -1;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    public static String[] extractStringArray(String json, String key) {
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start == -1) return new String[0];
        start += search.length();
        int end = json.indexOf("]", start);
        String arrayContent = json.substring(start, end).trim();
        if (arrayContent.isEmpty()) return new String[0];

        java.util.List<String> items = new java.util.ArrayList<>();
        boolean inQuotes = false;
        int itemStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '"' && !inQuotes) {
                inQuotes = true;
                itemStart = i + 1;
            } else if (c == '"' && inQuotes) {
                inQuotes = false;
                items.add(arrayContent.substring(itemStart, i));
            }
        }
        return items.toArray(new String[0]);
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
