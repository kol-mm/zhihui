package com.aiknowledge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String avatarUrl;
    private String nickname;
    private String signature;
    private String status;
    private String role;
    private String publishPolicy;
    private Boolean superAdmin;
    private Boolean messagingEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** Bound by the member; verification is optional. uk_user_verified_email keeps verified addresses unique. */
    private String email;
    private LocalDateTime emailVerifiedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Boolean getSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(Boolean superAdmin) { this.superAdmin = superAdmin; }

    public String getPublishPolicy() { return publishPolicy; }
    public void setPublishPolicy(String publishPolicy) { this.publishPolicy = publishPolicy; }
    public Boolean getMessagingEnabled() { return messagingEnabled; }
    public void setMessagingEnabled(Boolean messagingEnabled) { this.messagingEnabled = messagingEnabled; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }
}
