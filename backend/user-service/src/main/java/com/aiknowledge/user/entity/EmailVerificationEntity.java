package com.aiknowledge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("email_verification")
public class EmailVerificationEntity {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private String email;
    private String codeHash;
    private LocalDateTime expiresAt;
    private Integer failedAttempts;
    private LocalDateTime sentAt;
    private LocalDate sentDay;
    private Integer sentCount;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Integer getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(Integer failedAttempts) { this.failedAttempts = failedAttempts; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDate getSentDay() { return sentDay; }
    public void setSentDay(LocalDate sentDay) { this.sentDay = sentDay; }
    public Integer getSentCount() { return sentCount; }
    public void setSentCount(Integer sentCount) { this.sentCount = sentCount; }
}
