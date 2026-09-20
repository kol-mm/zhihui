package com.aiknowledge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_audit_log")
public class AdminAuditLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime createdAt;
    private Long actorId;
    private String actorName;
    private String action;
    private String category;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private Long subjectUserId;
    private String summary;
    private String detail;
    private String source;
    private String clientIp;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetLabel() { return targetLabel; }
    public void setTargetLabel(String targetLabel) { this.targetLabel = targetLabel; }
    public Long getSubjectUserId() { return subjectUserId; }
    public void setSubjectUserId(Long subjectUserId) { this.subjectUserId = subjectUserId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
}
