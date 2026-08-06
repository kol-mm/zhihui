package com.aiknowledge.community.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Service
public class CommunityNotificationClient {
    private static final Logger log = LoggerFactory.getLogger(CommunityNotificationClient.class);

    private final boolean enabled;
    private final String url;
    private final String token;
    private final RestClient restClient;

    public CommunityNotificationClient(
            @Value("${community.notification.enabled:true}") boolean enabled,
            @Value("${community.notification.url:http://127.0.0.1:8104/internal/notification}") String url,
            @Value("${community.notification.token:ai-knowledge-local-internal}") String token
    ) {
        this.enabled = enabled;
        this.url = url;
        this.token = token;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public void commentCreated(Long postOwnerId, Long commenterId, Long postId, String content) {
        if (!enabled || postOwnerId == null || postOwnerId.equals(commenterId)) return;
        try {
            restClient.post().uri(url).header("X-Internal-Token", token).body(Map.of(
                    "userId", postOwnerId,
                    "type", "COMMENT",
                    "title", "你的帖子收到新评论",
                    "content", "帖子 #" + postId + "：" + abbreviate(content)
            )).retrieve().toBodilessEntity();
        } catch (Exception error) {
            log.warn("Failed to create comment notification for post {}: {}", postId, error.getMessage());
        }
    }

    private String abbreviate(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }
}
