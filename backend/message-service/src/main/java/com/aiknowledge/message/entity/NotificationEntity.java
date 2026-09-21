package com.aiknowledge.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notification")
public class NotificationEntity {
    /** What a notification can open: a post, a knowledge file, a private conversation or a feedback ticket. */
    public static final java.util.Set<String> TARGET_TYPES = java.util.Set.of("POST", "KNOWLEDGE", "CHAT", "TICKET");

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Integer isRead;
    private LocalDateTime createdAt;
    /** What the notification opens (one of TARGET_TYPES), its id, and optionally the item inside it to show. */
    private String targetType;
    private Long targetId;
    private Long anchorId;
    /** Who caused the notification: the member who replied or commented. */
    private Long actorUserId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getIsRead() { return isRead; }
    public void setIsRead(Integer isRead) { this.isRead = isRead; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getAnchorId() { return anchorId; }
    public void setAnchorId(Long anchorId) { this.anchorId = anchorId; }

    public NotificationEntity linkTo(String targetType, Long targetId, Long anchorId) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.anchorId = anchorId;
        return this;
    }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
}
