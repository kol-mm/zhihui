package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.EmailVerificationEntity;
import com.aiknowledge.user.mapper.EmailVerificationMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlEmailVerificationStore implements EmailVerificationStore {
    private final EmailVerificationMapper mapper;

    public MySqlEmailVerificationStore(EmailVerificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PendingCode> find(Long userId) {
        EmailVerificationEntity row = mapper.selectById(userId);
        return row == null ? Optional.empty() : Optional.of(new PendingCode(row.getUserId(), row.getEmail(), row.getCodeHash(),
                row.getExpiresAt(), row.getFailedAttempts() == null ? 0 : row.getFailedAttempts(), row.getSentAt(),
                row.getSentDay(), row.getSentCount() == null ? 0 : row.getSentCount()));
    }

    @Override
    @Transactional
    public void save(PendingCode code) {
        EmailVerificationEntity row = new EmailVerificationEntity();
        row.setUserId(code.userId());
        row.setEmail(code.email());
        row.setCodeHash(code.codeHash());
        row.setExpiresAt(code.expiresAt());
        row.setFailedAttempts(code.failedAttempts());
        row.setSentAt(code.sentAt());
        row.setSentDay(code.sentDay());
        row.setSentCount(code.sentCount());
        if (mapper.updateById(row) == 0) mapper.insert(row);
    }

    @Override
    public void recordFailure(Long userId) {
        mapper.recordFailure(userId);
    }
}
