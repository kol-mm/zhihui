package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.security.TokenRevocations;
import com.aiknowledge.user.storage.UserAvatarStorageService;
import com.aiknowledge.user.store.InMemoryProfileAuditStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.ProfileAuditStore;
import com.aiknowledge.user.store.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The 资料审核 queue: when a profile change waits for review, and what an administrator can do with it. */
class ProfileAuditTest {
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

    // Each test gets its own store directory, so counting the whole queue means counting only this test's work.
    {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/profile-audit-" + System.nanoTime());
    }

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserStore users = new InMemoryUserStore(encoder);
    private final ProfileAuditStore queue = new InMemoryProfileAuditStore();
    private final List<AdminAudit.Event> recorded = new ArrayList<>();

    private final UserController reviewing = controller(true, "");
    private final UserController immediate = controller(false, "");

    private UserController controller(boolean reviewRequired, String platformName) {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("profile_audit_required", false)).thenReturn(reviewRequired);
        when(config.text("platform_name", "")).thenReturn(platformName);
        UserController controller = new UserController(users, encoder,
                new UserAvatarStorageService("local", "target/test-user-avatars", "http://127.0.0.1:9000", "test", "test", "test"),
                new CaptchaService(), config, new LoginAttemptGuard(), TokenRevocations.inMemory(), queue);
        controller.setAdminAudit((authorization, event) -> {
            if (LocalAuth.isAdmin(authorization)) recorded.add(event);
        });
        return controller;
    }

    @Test
    void withTheSwitchOffProfileEditsApplyAtOnce() {
        UserEntity member = member("STANDARD");
        String auth = auth(member);
        Map<String, Object> saved = immediate.updateProfile(auth, profile("新昵称", "新签名")).data();

        assertEquals("新昵称", saved.get("nickname"));
        assertNull(saved.get("profileAudit"), "nothing is waiting");
        assertEquals("新昵称", users.findById(member.getId()).orElseThrow().getNickname());
        assertEquals(0, queue.countPending());
    }

    @Test
    void aPreReviewedMemberIsQueuedEvenWithTheSwitchOff() {
        UserEntity member = member("PRE_REVIEW");
        Map<String, Object> saved = immediate.updateProfile(auth(member), profile("待审昵称", "待审签名")).data();

        Map<?, ?> pending = (Map<?, ?>) saved.get("profileAudit");
        assertEquals("PENDING", pending.get("status"));
        assertEquals("待审昵称", pending.get("nickname"));
        assertEquals(member.getNickname(), saved.get("nickname"), "the live profile is unchanged");
        assertEquals(member.getNickname(), users.findById(member.getId()).orElseThrow().getNickname());
    }

    @Test
    void editingAgainReplacesTheWaitingChange() {
        UserEntity member = member("STANDARD");
        String auth = auth(member);
        reviewing.updateProfile(auth, profile("第一次", "签名一"));
        long firstId = ((Number) ((Map<?, ?>) reviewing.info(auth, member.getUsername()).data().get("profileAudit")).get("id")).longValue();
        reviewing.updateProfile(auth, profile("第二次", "签名二"));

        assertEquals(1, queue.countPending(), "one member, one place in the queue");
        ProfileAuditStore.ProfileChange open = queue.findOpen(member.getId()).orElseThrow();
        assertEquals(firstId, open.id(), "the queue position is kept");
        assertEquals("第二次", open.nickname());
        assertEquals("签名二", open.signature());
    }

    @Test
    void theAvatarIsNotReviewedAndAppliesAtOnce() {
        UserEntity member = member("STANDARD");
        String auth = auth(member);
        Map<String, Object> request = profile("新昵称", "新签名");
        request.put("avatarUrl", "/user/avatar/new-token.png");
        Map<String, Object> saved = reviewing.updateProfile(auth, request).data();

        assertEquals("/user/avatar/new-token.png", saved.get("avatarUrl"));
        assertEquals("/user/avatar/new-token.png", users.findById(member.getId()).orElseThrow().getAvatarUrl());
        assertEquals("新昵称", ((Map<?, ?>) saved.get("profileAudit")).get("nickname"), "the text still waits");
        assertEquals(member.getNickname(), saved.get("nickname"));
    }

    @Test
    void savingWithoutAChangeDoesNotQueueAnything() {
        UserEntity member = member("STANDARD");
        reviewing.updateProfile(auth(member), profile(member.getNickname(), member.getSignature()));
        assertEquals(0, queue.countPending());
    }

    @Test
    void onlyTheMemberAndAdministratorsSeeTheWaitingChange() {
        UserEntity member = member("STANDARD");
        String auth = auth(member);
        reviewing.updateProfile(auth, profile("待审昵称", "待审签名"));
        UserEntity other = member("STANDARD");

        assertNotNull(reviewing.info(auth, member.getUsername()).data().get("profileAudit"));
        assertNotNull(reviewing.info(ADMIN, member.getUsername()).data().get("profileAudit"));
        Map<String, Object> card = reviewing.info(auth(other), member.getUsername()).data();
        assertFalse(card.containsKey("profileAudit"));
        assertFalse(card.toString().contains("待审昵称"), "a waiting nickname never reaches other members");
    }

    @Test
    void theQueueIsCountedPagedAndFiltered() {
        List<UserEntity> members = List.of(member("STANDARD"), member("STANDARD"), member("STANDARD"));
        for (int index = 0; index < members.size(); index++) {
            reviewing.updateProfile(auth(members.get(index)), profile("排队昵称" + index, "签名" + index));
        }
        assertEquals(3L, reviewing.adminOverview(ADMIN).data().get("pendingAudits"));

        Map<String, Object> first = page(null, null, null, 2);
        assertEquals(2, items(first).size());
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, first.get("total"));
        Map<String, Object> second = page(null, null, ((Number) first.get("nextCursor")).longValue(), 2);
        assertEquals(1, items(second).size());
        assertNull(second.get("total"), "counted only on the first page");

        assertEquals(1, items(page("排队昵称1", null, null, 20)).size(), "keyword matches the proposed nickname");
        // One box searches the nickname, the member id and the change id, so a number can match either id.
        List<Map<String, Object>> byMember = items(page(String.valueOf(members.get(0).getId()), null, null, 20));
        assertTrue(byMember.stream().anyMatch(row -> members.get(0).getId().equals(row.get("userId"))), "found by member id");
        assertEquals(0, items(page("999999", null, null, 20)).size(), "an id nobody has finds nothing");
        assertEquals(3, items(page(null, "PENDING", null, 20)).size());
        assertEquals(0, items(page(null, "APPROVED", null, 20)).size());

        assertEquals(500, reviewing.adminProfileChangesPage(ADMIN, null, "GONE", null, 20).code());
        assertEquals(500, reviewing.adminProfileChangesPage(ADMIN, null, null, -1L, 20).code());
        assertEquals(500, reviewing.adminProfileChangesPage(auth(members.get(0)), null, null, null, 20).code());
    }

    @Test
    void approvalAppliesTheChangeAndIsRecorded() {
        UserEntity member = member("STANDARD");
        String auth = auth(member);
        String before = member.getNickname();
        reviewing.updateProfile(auth, profile("通过昵称", "通过签名"));
        long changeId = openId(member);

        Map<String, Object> decided = reviewing.auditProfileChange(ADMIN, Map.of("changeId", changeId, "status", "APPROVED")).data();

        assertEquals("APPROVED", decided.get("status"));
        UserEntity updated = users.findById(member.getId()).orElseThrow();
        assertEquals("通过昵称", updated.getNickname());
        assertEquals("通过签名", updated.getSignature());
        assertEquals(0, queue.countPending());
        assertNull(reviewing.info(auth, member.getUsername()).data().get("profileAudit"), "nothing left to report");

        AdminAudit.Event event = recorded.get(recorded.size() - 1);
        assertEquals("USER_PROFILE_AUDIT", event.action());
        assertEquals(AdminAudit.ACCOUNTS, event.category());
        assertEquals(member.getId(), event.subjectUserId());
        assertEquals("通过会员资料修改", event.summary());
        assertTrue(event.detail().get("changes").toString().contains(before));
    }

    @Test
    void rejectionKeepsTheProfileAndTellsTheMemberWhy() {
        UserEntity member = member("STANDARD");
        String auth = auth(member);
        reviewing.updateProfile(auth, profile("驳回昵称", "驳回签名"));
        long changeId = openId(member);

        reviewing.auditProfileChange(ADMIN, Map.of("changeId", changeId, "status", "REJECTED", "reason", "昵称不符合规范"));

        assertEquals(member.getNickname(), users.findById(member.getId()).orElseThrow().getNickname());
        Map<?, ?> state = (Map<?, ?>) reviewing.info(auth, member.getUsername()).data().get("profileAudit");
        assertEquals("REJECTED", state.get("status"));
        assertEquals("昵称不符合规范", state.get("reason"));
        assertEquals("驳回会员资料修改", recorded.get(recorded.size() - 1).summary());

        // Deciding twice is refused, whichever way.
        assertEquals("该资料修改已经被驳回", reviewing.auditProfileChange(ADMIN, Map.of("changeId", changeId, "status", "REJECTED")).message());
        assertEquals("当前状态不支持此审核操作", reviewing.auditProfileChange(ADMIN, Map.of("changeId", changeId, "status", "APPROVED")).message());
    }

    @Test
    void aRejectionWithoutAReasonStillSaysSomething() {
        UserEntity member = member("STANDARD");
        reviewing.updateProfile(auth(member), profile("无理由", "签名"));
        reviewing.auditProfileChange(ADMIN, Map.of("changeId", openId(member), "status", "REJECTED"));
        assertEquals("资料未通过审核", ((Map<?, ?>) reviewing.info(auth(member), member.getUsername()).data().get("profileAudit")).get("reason"));
    }

    @Test
    void decisionsAreRefusedForUnknownChangesAndResults() {
        assertEquals("资料修改申请不存在", reviewing.auditProfileChange(ADMIN, Map.of("changeId", 9999, "status", "APPROVED")).message());
        assertEquals("审核结果无效", reviewing.auditProfileChange(ADMIN, Map.of("changeId", 1, "status", "PENDING")).message());
        UserEntity member = member("STANDARD");
        reviewing.updateProfile(auth(member), profile("无权", "签名"));
        assertEquals(500, reviewing.auditProfileChange(auth(member), Map.of("changeId", openId(member), "status", "APPROVED")).code());
    }

    @Test
    void aNicknameThatImpersonatesStaffCannotBeApprovedLater() {
        // The platform renamed itself after the member submitted, so the nickname only collides now.
        UserEntity member = member("STANDARD");
        reviewing.updateProfile(auth(member), profile("星云小站", "签名"));
        long changeId = openId(member);
        UserController named = controller(true, "星云");

        assertEquals("该昵称与平台工作人员重名，请驳回该申请",
                named.auditProfileChange(ADMIN, Map.of("changeId", changeId, "status", "APPROVED")).message());
        assertEquals(member.getNickname(), users.findById(member.getId()).orElseThrow().getNickname());
        assertEquals(0, named.auditProfileChange(ADMIN, Map.of("changeId", changeId, "status", "REJECTED")).code());
    }

    @Test
    void theQueueShowsWhenTheAccountMovedOnAndAdminEditsStillApplyAtOnce() {
        UserEntity member = member("STANDARD");
        reviewing.updateProfile(auth(member), profile("会员昵称", "会员签名"));
        assertFalse((Boolean) items(page(null, null, null, 20)).get(0).get("stale"));

        // An administrator edits the same account while the change waits; governance never queues.
        assertEquals(0, reviewing.updateUserGovernance(ADMIN, Map.of("userId", member.getId(), "nickname", "管理员改名")).code());
        assertEquals("管理员改名", users.findById(member.getId()).orElseThrow().getNickname());
        assertEquals(1, queue.countPending(), "the member's change still waits");

        Map<String, Object> row = items(page(null, null, null, 20)).get(0);
        assertEquals(true, row.get("stale"), "the administrator is told the account moved");
        assertEquals("管理员改名", ((Map<?, ?>) row.get("before")).get("nickname"), "before is what the account looks like now");
        assertEquals("会员昵称", ((Map<?, ?>) row.get("after")).get("nickname"));
    }

    private Map<String, Object> page(String keyword, String status, Long cursor, int limit) {
        ApiResponse<Map<String, Object>> response = reviewing.adminProfileChangesPage(ADMIN, keyword, status, cursor, limit);
        assertEquals(0, response.code(), response.message());
        return response.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private long openId(UserEntity member) {
        return queue.findOpen(member.getId()).orElseThrow().id();
    }

    private Map<String, Object> profile(String nickname, String signature) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("nickname", nickname);
        request.put("signature", signature == null ? "" : signature);
        return request;
    }

    private UserEntity member(String publishPolicy) {
        UserEntity user = new UserEntity();
        user.setUsername("profile-" + System.nanoTime());
        user.setPasswordHash(encoder.encode("old-pass1"));
        user.setNickname("原昵称" + System.nanoTime() % 1000);
        user.setSignature("原签名");
        user.setStatus("ACTIVE");
        user.setRole("USER");
        user.setPublishPolicy(publishPolicy);
        return users.save(user);
    }

    private static String auth(UserEntity user) {
        return "Bearer " + LocalAuth.issueToken(user.getUsername(), user.getId(), "USER");
    }
}
