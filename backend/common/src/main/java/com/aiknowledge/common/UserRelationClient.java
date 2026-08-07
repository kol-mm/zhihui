package com.aiknowledge.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class UserRelationClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final String relationUrl;
    private final String internalToken;

    public UserRelationClient(
            ObjectMapper objectMapper,
            @Value("${platform.user-relation-url:http://127.0.0.1:8101/user/internal/relation}") String relationUrl,
            @Value("${platform.internal-user-token:ai-knowledge-local-internal}") String internalToken
    ) {
        this.objectMapper = objectMapper;
        this.relationUrl = relationUrl;
        this.internalToken = internalToken;
    }

    public boolean interactionAllowed(long userId, long targetUserId) {
        if (userId <= 0 || targetUserId <= 0 || userId == targetUserId) return userId == targetUserId;
        return fetchPolicy(userId, targetUserId).allowed();
    }

    public boolean publishingAllowed(long userId) {
        return userId > 0 && fetchPolicy(userId, userId).publishAllowed();
    }

    public boolean preAuditRequired(long userId) {
        return userId > 0 && fetchPolicy(userId, userId).preAuditRequired();
    }

    public boolean messagingAllowed(long userId, long targetUserId) {
        return userId > 0 && targetUserId > 0 && fetchPolicy(userId, targetUserId).messagingAllowed();
    }

    private RelationPolicy fetchPolicy(long userId, long targetUserId) {
        try {
            String url = relationUrl + "?userId=" + encode(userId) + "&targetUserId=" + encode(targetUserId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(800))
                    .header("X-Internal-Token", internalToken)
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return RelationPolicy.DENIED;
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("code").asInt(500) != 0) return RelationPolicy.DENIED;
            JsonNode data = root.path("data");
            return new RelationPolicy(
                    data.path("allowed").asBoolean(false),
                    data.path("publishAllowed").asBoolean(false),
                    data.path("preAuditRequired").asBoolean(false),
                    data.path("messagingAllowed").asBoolean(false)
            );
        } catch (Exception ignored) {
            return RelationPolicy.DENIED;
        }
    }

    private String encode(long value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }

    private record RelationPolicy(boolean allowed, boolean publishAllowed, boolean preAuditRequired,
                                  boolean messagingAllowed) {
        private static final RelationPolicy DENIED = new RelationPolicy(false, false, false, false);
    }
}
