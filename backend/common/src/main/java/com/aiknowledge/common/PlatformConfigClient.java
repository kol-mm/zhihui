package com.aiknowledge.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class PlatformConfigClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final String configUrl;

    public PlatformConfigClient(ObjectMapper objectMapper,
                                @Value("${platform.ai-config-url:http://127.0.0.1:8200/ai/config/public}") String configUrl) {
        this.objectMapper = objectMapper;
        this.configUrl = configUrl;
    }

    public int maxUploadMb() {
        return Math.max(1, integer("max_upload_mb", 25));
    }

    public boolean enabled(String key, boolean fallback) {
        JsonNode data = read();
        return data == null ? fallback : data.path(key).asBoolean(fallback);
    }

    public int integer(String key, int fallback) {
        JsonNode data = read();
        return data == null ? fallback : data.path(key).asInt(fallback);
    }

    public String text(String key, String fallback) {
        JsonNode data = read();
        if (data == null || !data.hasNonNull(key)) return fallback;
        String value = data.path(key).asText(fallback).trim();
        return value.isBlank() ? fallback : value;
    }

    private JsonNode read() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(configUrl))
                    .timeout(Duration.ofMillis(700)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("data");
        } catch (Exception ignored) {
            return null;
        }
    }
}
