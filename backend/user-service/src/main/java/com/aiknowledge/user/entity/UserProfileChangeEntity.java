package com.aiknowledge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** A member's proposed nickname and signature, waiting for an administrator's decision. */
@TableName("user_profile_change")
public class UserProfileChangeEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String nickname;
    private String signature;
    private String beforeNickname;
    private String beforeSignature;
    private String status;
    private String reason;
    private Long reviewerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // open_user_id is a generated column; the database maintains it and MyBatis-Plus must not write it.

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getBeforeNickname() { return beforeNickname; }
    public void setBeforeNickname(String beforeNickname) { this.beforeNickname = beforeNickname; }
    public String getBeforeSignature() { return beforeSignature; }
    public void setBeforeSignature(String beforeSignature) { this.beforeSignature = beforeSignature; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
