package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface UserStore {
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findById(Long userId);

    List<UserEntity> findByIds(List<Long> userIds);

    UserEntity save(UserEntity user);

    Optional<UserEntity> updateProfile(Long userId, String nickname, String avatarUrl, String signature);

    Optional<UserEntity> updatePassword(Long userId, String passwordHash);

    List<UserEntity> listUsers();

    Optional<UserEntity> updateStatus(Long userId, String status);

    Optional<UserEntity> updateGovernance(Long userId, String role, String publishPolicy, boolean messagingEnabled);

    boolean follow(Long userId, Long targetUserId);

    boolean unfollow(Long userId, Long targetUserId);

    List<Long> listFollowTargets(Long userId);

    List<Long> listFollowerIds(Long userId);

    boolean block(Long userId, Long targetUserId);

    boolean unblock(Long userId, Long targetUserId);

    List<Long> listBlockedIds(Long userId);

    UserReport reportUser(Long reporterId, Long targetUserId, String reason);

    List<UserReport> listUserReports();

    Optional<UserReport> resolveUserReport(Long reportId, String status, String result);

    BehaviorRecord recordBehavior(Long userId, String action, String targetType, Long targetId);

    List<BehaviorRecord> listBehaviors(Long userId);

    record UserReport(Long id, Long reporterId, Long targetUserId, String reason, String status,
                      String result, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    record BehaviorRecord(Long id, Long userId, String action, String targetType, Long targetId,
                          LocalDateTime createdAt) {
    }

    /** Counters behind the user admin overview. */
    record UserTotals(long total, long active, long risk, long admins) { }

    /** MySQL answers this with one grouped query; the in-memory profile folds the user list. */
    default UserTotals userTotals() {
        List<UserEntity> users = listUsers();
        long active = users.stream().filter(user -> "ACTIVE".equals(user.getStatus())).count();
        long admins = users.stream().filter(user -> "ADMIN".equals(user.getRole())).count();
        return new UserTotals(users.size(), active, users.size() - active, admins);
    }

    /** Number of user reports, without reading them. */
    default long countUserReports() {
        return listUserReports().size();
    }


    /**
     * One page of the admin user table.
     *
     * @param keyword matches the id, username or nickname (optional)
     * @param status  ACTIVE or DISABLED (optional)
     * @param role    ADMIN or USER (optional)
     * @param userId  a single account, used when opening one user from a report (optional)
     * @param afterId cursor: only users with a larger id, because the table reads oldest first
     */
    record UserPageQuery(String keyword, String status, String role, Long userId, Long afterId, int limit) { }

    /** MySQL pages this by keyset; the in-memory profile filters the user list the same way. */
    default List<UserEntity> pageUsers(UserPageQuery query) {
        return matchingUsers(query)
                .filter(user -> query.afterId() == null || user.getId() > query.afterId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    /** How many users match the filters, ignoring the cursor. */
    default long countUsers(UserPageQuery query) {
        return matchingUsers(query).count();
    }

    private java.util.stream.Stream<UserEntity> matchingUsers(UserPageQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listUsers().stream()
                .sorted(java.util.Comparator.comparing(UserEntity::getId))
                .filter(user -> query.userId() == null || query.userId().equals(user.getId()))
                .filter(user -> query.status() == null || query.status().isBlank() || query.status().equals(user.getStatus()))
                .filter(user -> query.role() == null || query.role().isBlank() || query.role().equals(user.getRole()))
                .filter(user -> keyword.isEmpty()
                        || String.valueOf(user.getId()).contains(keyword)
                        || (user.getUsername() != null && user.getUsername().toLowerCase().contains(keyword))
                        || (user.getNickname() != null && user.getNickname().toLowerCase().contains(keyword)));
    }


    /** User reports that still need a decision, for the moderation badge. */
    default long countOpenUserReports() {
        return listUserReports().stream().filter(report -> !"RESOLVED".equals(report.status())).count();
    }


    /**
     * One page of the moderation report queue, newest first.
     *
     * @param keyword  matches the report id, the reported user id or the reason (optional)
     * @param status   PENDING, PROCESSING or RESOLVED (optional)
     * @param beforeId cursor: only reports with a smaller id (optional)
     */
    record AdminReportQuery(String keyword, String status, Long beforeId, int limit) { }

    default List<UserReport> pageUserReports(AdminReportQuery query) {
        return matchingUserReports(query)
                .filter(report -> query.beforeId() == null || report.id() < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    default long countUserReports(AdminReportQuery query) {
        return matchingUserReports(query).count();
    }

    private java.util.stream.Stream<UserReport> matchingUserReports(AdminReportQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listUserReports().stream()
                .sorted(java.util.Comparator.comparing(UserReport::id).reversed())
                .filter(report -> query.status() == null || query.status().isBlank() || query.status().equals(report.status()))
                .filter(report -> keyword.isEmpty()
                        || String.valueOf(report.id()).contains(keyword)
                        || String.valueOf(report.targetUserId()).contains(keyword)
                        || (report.reason() != null && report.reason().toLowerCase().contains(keyword)));
    }

}
