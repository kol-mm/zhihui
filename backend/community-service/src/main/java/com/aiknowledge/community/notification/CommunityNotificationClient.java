package com.aiknowledge.community.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
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

    /** Who hears about a new comment, and what the notification opens. */
    public enum CommentAudience {
        /** The post's author, about a comment or a reply anywhere in the post's discussion. */
        POST_AUTHOR("COMMENT", "你的帖子收到新评论"),
        /** The author of the comment that was replied to. */
        REPLIED_AUTHOR("REPLY", "你的评论收到新回复");

        private final String type;
        private final String title;

        CommentAudience(String type, String title) {
            this.type = type;
            this.title = title;
        }
    }

    /**
     * Notifies {@code recipientId} of comment {@code commentId}; the notification links to the post and scrolls
     * to the comment. Failures are logged, never passed on: the comment itself is already saved.
     */
    public void commentCreated(CommentAudience audience, Long recipientId, Long commenterId,
                               Long postId, String postTitle, Long commentId, String content) {
        if (!enabled || recipientId == null || recipientId.equals(commenterId)) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", recipientId);
        body.put("actorUserId", commenterId);
        body.put("type", audience.type);
        body.put("title", audience.title);
        body.put("content", postLabel(postId, postTitle) + "：" + abbreviate(content, 120));
        body.put("targetType", "POST");
        body.put("targetId", postId);
        body.put("anchorId", commentId);
        try {
            restClient.post().uri(url).header("X-Internal-Token", token).body(body).retrieve().toBodilessEntity();
        } catch (Exception error) {
            log.warn("Failed to create comment notification for post {}: {}", postId, error.getMessage());
        }
    }

    private static String postLabel(Long postId, String postTitle) {
        String title = postTitle == null ? "" : postTitle.trim();
        return title.isEmpty() ? "帖子 #" + postId : "《" + abbreviate(title, 40) + "》";
    }

    private static String abbreviate(String value, int limit) {
        String text = value == null ? "" : value.trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
