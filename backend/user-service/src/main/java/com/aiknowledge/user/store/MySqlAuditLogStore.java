package com.aiknowledge.user.store;

import com.aiknowledge.common.AppTime;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.user.entity.AdminAuditLogEntity;
import com.aiknowledge.user.mapper.AdminAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@Profile("mysql")
public class MySqlAuditLogStore implements AuditLogStore {
    private static final Logger log = LoggerFactory.getLogger(MySqlAuditLogStore.class);
    /** TEXT holds 65,535 bytes; details are small, and anything larger is replaced by a marker. */
    private static final int MAX_DETAIL_CHARS = 16_000;

    private final AdminAuditLogMapper mapper;
    private final ObjectMapper objectMapper;

    public MySqlAuditLogStore(AdminAuditLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public StoredEntry save(AuditEntry entry) {
        AdminAuditLogEntity row = new AdminAuditLogEntity();
        row.setCreatedAt(LocalDateTime.ofInstant(entry.occurredAt(), AppTime.storageZone()));
        row.setActorId(entry.actorId());
        row.setActorName(entry.actorName());
        row.setAction(entry.action());
        row.setCategory(entry.category());
        row.setTargetType(entry.targetType());
        row.setTargetId(entry.targetId());
        row.setTargetLabel(entry.targetLabel());
        row.setSubjectUserId(entry.subjectUserId());
        row.setSummary(entry.summary());
        row.setDetail(detailText(entry.detail()));
        row.setSource(entry.source());
        row.setClientIp(entry.clientIp());
        mapper.insert(row);
        return toStored(row);
    }

    @Override
    public List<StoredEntry> page(Query query) {
        LambdaQueryWrapper<AdminAuditLogEntity> where = Wrappers.<AdminAuditLogEntity>lambdaQuery()
                .lt(query.beforeId() != null, AdminAuditLogEntity::getId, query.beforeId())
                .eq(AuditLogStore.blank(query.category()) != null, AdminAuditLogEntity::getCategory, query.category())
                .eq(AuditLogStore.blank(query.action()) != null, AdminAuditLogEntity::getAction, query.action())
                .eq(query.subjectUserId() != null, AdminAuditLogEntity::getSubjectUserId, query.subjectUserId())
                .ge(query.from() != null, AdminAuditLogEntity::getCreatedAt, query.from())
                .lt(query.to() != null, AdminAuditLogEntity::getCreatedAt, query.to());
        String actor = AuditLogStore.blank(query.actor());
        if (actor != null) {
            Long actorId = AuditLogStore.numeric(actor);
            where.and(nested -> nested.like(AdminAuditLogEntity::getActorName, actor)
                    .or(actorId != null).eq(actorId != null, AdminAuditLogEntity::getActorId, actorId));
        }
        String keyword = AuditLogStore.blank(query.keyword());
        if (keyword != null) {
            where.and(nested -> nested.like(AdminAuditLogEntity::getTargetLabel, keyword)
                    .or().like(AdminAuditLogEntity::getSummary, keyword)
                    .or().eq(AdminAuditLogEntity::getTargetId, keyword));
        }
        where.orderByDesc(AdminAuditLogEntity::getId).last("LIMIT " + Math.max(0, Math.min(query.limit(), 500)));
        return mapper.selectList(where).stream().map(this::toStored).toList();
    }

    @Override
    public int removeBefore(LocalDateTime cutoff) {
        return mapper.delete(Wrappers.<AdminAuditLogEntity>lambdaQuery().lt(AdminAuditLogEntity::getCreatedAt, cutoff));
    }

    private StoredEntry toStored(AdminAuditLogEntity row) {
        AuditEntry entry = new AuditEntry(row.getCreatedAt().atZone(AppTime.storageZone()).toInstant(),
                row.getActorId(), row.getActorName(), row.getAction(), row.getCategory(), row.getTargetType(),
                row.getTargetId(), row.getTargetLabel(), row.getSubjectUserId(), row.getSummary(),
                detailMap(row.getDetail()), row.getSource(), row.getClientIp());
        return new StoredEntry(row.getId(), row.getCreatedAt(), entry);
    }

    private String detailText(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            String text = objectMapper.writeValueAsString(detail);
            return text.length() <= MAX_DETAIL_CHARS ? text : "{\"truncated\":true}";
        } catch (Exception failure) {
            log.warn("Audit detail could not be stored: {}", failure.toString());
            return null;
        }
    }

    private Map<String, Object> detailMap(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(text, new TypeReference<>() { });
        } catch (Exception failure) {
            return Map.of("unreadable", true);
        }
    }
}
