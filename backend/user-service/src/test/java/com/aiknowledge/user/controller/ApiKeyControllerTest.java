package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.ApiKeyScopes;
import com.aiknowledge.user.store.ApiKeyStore;
import com.aiknowledge.user.store.InMemoryApiKeyStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/api-keys-" + System.nanoTime());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T08:00:00Z");

        void advance(Duration step) { now = now.plus(step); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final String INTERNAL = "ai-knowledge-local-internal";

    private final UserStore users = new InMemoryUserStore(new BCryptPasswordEncoder(4));
    private final ApiKeyStore keys = new InMemoryApiKeyStore();
    private final MutableClock clock = new MutableClock();
    private final List<AdminAudit.Event> audited = new ArrayList<>();
    private final ApiKeyController controller = new ApiKeyController(keys, users, clock);

    {
        controller.setAdminAudit((authorization, event) -> audited.add(event));
    }

    private final UserEntity superAdmin = account("root", "ADMIN");
    private final String superAdminToken = "Bearer " + LocalAuth.issueToken(superAdmin.getUsername(), superAdmin.getId(), "ADMIN", true);
    private final UserEntity ordinaryAdmin = account("helper", "ADMIN");
    private final String ordinaryAdminToken = "Bearer " + LocalAuth.issueToken(ordinaryAdmin.getUsername(), ordinaryAdmin.getId(), "ADMIN", false);
    private final UserEntity member = account("member", "USER");
    private final String memberToken = "Bearer " + LocalAuth.issueToken(member.getUsername(), member.getId(), "USER");

    @Test
    void onlyASuperAdministratorCanManageKeys() {
        Map<String, Object> request = settings("integration", List.of("knowledge:read"), null, null);
        for (String caller : new String[]{null, "Bearer not-a-token", memberToken, ordinaryAdminToken}) {
            assertEquals("需要超级管理员权限", controller.list(caller).message());
            assertEquals("需要超级管理员权限", controller.create(caller, request).message());
            assertEquals("需要超级管理员权限", controller.update(caller, Map.of("keyId", 1)).message());
            assertEquals("需要超级管理员权限", controller.rotate(caller, Map.of("keyId", 1)).message());
            assertEquals("需要超级管理员权限", controller.revoke(caller, Map.of("keyId", 1)).message());
        }
        assertEquals(0, controller.list(superAdminToken).code());
    }

    @Test
    void theSecretIsShownOnceAndOnlyItsDigestIsKept() {
        Map<String, Object> created = ok(controller.create(superAdminToken, settings("报表同步", List.of("knowledge:read"), null, null)));
        String secret = (String) created.get("secret");

        assertTrue(secret.startsWith("zk_"));
        assertEquals(3 + 43, secret.length(), "zk_ and 32 random bytes in base64url");
        assertEquals(secret.substring(0, 11), created.get("prefix"));

        ApiKeyStore.ApiKey stored = keys.find((Long) created.get("id")).orElseThrow();
        assertEquals(ApiKeyController.digest(secret), stored.keyHash());
        assertNotEquals(secret, stored.keyHash());

        // Nothing the list returns, and nothing the audit trail keeps, carries the secret or its digest.
        String listed = String.valueOf(ok(controller.list(superAdminToken)));
        assertFalse(listed.contains(secret));
        assertFalse(listed.contains(stored.keyHash()));
        assertFalse(listed.contains("secret"));
        assertFalse(String.valueOf(audited).contains(secret));
        assertEquals("API_KEY_CREATE", audited.get(0).action());
    }

    @Test
    void twoKeysNeverShareASecret() {
        String first = (String) ok(controller.create(superAdminToken, settings("a", List.of("knowledge:read"), null, null))).get("secret");
        String second = (String) ok(controller.create(superAdminToken, settings("b", List.of("knowledge:read"), null, null))).get("secret");
        assertNotEquals(first, second);
    }

    @Test
    void settingsAreChecked() {
        assertEquals("请填写接口密钥的名称", controller.create(superAdminToken, settings(" ", List.of("knowledge:read"), null, null)).message());
        assertEquals("名称不能超过 64 个字符", controller.create(superAdminToken, settings("x".repeat(65), List.of("knowledge:read"), null, null)).message());
        assertEquals("请至少选择一项权限", controller.create(superAdminToken, settings("k", List.of(), null, null)).message());
        assertEquals("未知的权限：admin:all", controller.create(superAdminToken, settings("k", List.of("admin:all"), null, null)).message());
        assertEquals("有效期不能早于今天", controller.create(superAdminToken, settings("k", List.of("knowledge:read"), null, "2026-09-26")).message());
        // Today is allowed: the key is valid through the end of the chosen day.
        assertEquals(0, controller.create(superAdminToken, settings("k", List.of("knowledge:read"), null, "2026-09-27")).code());
        assertEquals("有效期格式应为 yyyy-MM-dd", controller.create(superAdminToken, settings("k", List.of("knowledge:read"), null, "next year")).message());
    }

    @Test
    void aWriteScopeNeedsAnOrdinaryActiveAccountToActFor() {
        assertEquals("带写权限的接口密钥必须指定一个代为操作的账号，写入的内容归属该账号",
                controller.create(superAdminToken, settings("w", List.of("community:write"), null, null)).message());
        assertEquals("接口密钥不能代表管理员账号操作",
                controller.create(superAdminToken, settings("w", List.of("community:write"), ordinaryAdmin.getId(), null)).message());
        UserEntity suspended = account("suspended", "USER");
        users.updateStatus(suspended.getId(), "DISABLED");
        assertEquals("代为操作的账号未处于正常状态",
                controller.create(superAdminToken, settings("w", List.of("community:write"), suspended.getId(), null)).message());
        assertEquals("代为操作的账号不存在",
                controller.create(superAdminToken, settings("w", List.of("community:write"), 999_999L, null)).message());
        assertEquals(0, controller.create(superAdminToken, settings("w", List.of("community:write"), member.getId(), null)).code());
    }

    @Test
    void aLiveKeyVerifiesAndCarriesItsScopes() {
        String secret = secret(settings("reader", List.of("community:read", "knowledge:read"), null, null));
        Map<String, Object> answer = verify(secret);
        assertEquals(true, answer.get("valid"));
        // Stored in the catalogue's order, whatever order they were chosen in.
        assertEquals(List.of("knowledge:read", "community:read"), answer.get("scopes"));
        assertNull(answer.get("token"), "a key acting for nobody goes on anonymously");
    }

    @Test
    void aKeyActingForAnAccountIsHandedOnAsAShortLivedOrdinaryMember() {
        String secret = secret(settings("poster", List.of("community:write"), member.getId(), null));
        String token = (String) verify(secret).get("token");
        String authorization = "Bearer " + token;

        assertEquals(member.getId(), LocalAuth.userId(authorization));
        assertEquals("USER", LocalAuth.role(authorization));
        assertFalse(LocalAuth.isAdmin(authorization));
        assertFalse(LocalAuth.isSuperAdmin(authorization));
        assertTrue(LocalAuth.isDelegated(authorization));
        LocalAuth.TokenInfo info = LocalAuth.tokenInfo(authorization);
        assertTrue(info.expiresAt() - info.issuedAt() <= 60, "lives a minute, not a session");
        assertTrue(info.tokenId().startsWith("ak-"));
    }

    @Test
    void anythingButALiveKeyIsRefusedWithoutSayingWhy() {
        assertEquals(false, verify("zk_not-a-real-key").get("valid"));
        assertEquals(false, verify("sk_live_wrongprefix").get("valid"));
        assertEquals(false, verify("zk_" + "x".repeat(200)).get("valid"));
        assertEquals(false, verify("").get("valid"));

        // A caller without the internal token is refused outright rather than told the key is invalid, so a
        // misconfigured gateway shows up as an error instead of every key quietly failing.
        ApiResponse<Map<String, Object>> outsider = controller.verify("guess", Map.of("key", "zk_anything"));
        assertEquals(500, outsider.code());
        assertEquals(500, controller.verify(null, Map.of("key", "zk_anything")).code());
    }

    @Test
    void revokingStopsTheKeyAtOnceAndCannotBeUndone() {
        Map<String, Object> created = ok(controller.create(superAdminToken, settings("temp", List.of("knowledge:read"), null, null)));
        String secret = (String) created.get("secret");
        Object id = created.get("id");
        assertEquals(true, verify(secret).get("valid"));

        Map<String, Object> revoked = ok(controller.revoke(superAdminToken, Map.of("keyId", id, "reason", "合作结束")));
        assertEquals("REVOKED", revoked.get("status"));
        assertEquals(false, verify(secret).get("valid"));

        assertEquals("接口密钥已经撤销", controller.revoke(superAdminToken, Map.of("keyId", id)).message());
        assertEquals("已撤销的接口密钥不能修改",
                controller.update(superAdminToken, withKey(id, settings("again", List.of("knowledge:read"), null, null))).message());
        assertEquals("已撤销的接口密钥不能轮换", controller.rotate(superAdminToken, Map.of("keyId", id)).message());
        assertEquals(false, verify(secret).get("valid"));

        AdminAudit.Event event = audited.get(audited.size() - 1);
        assertEquals("API_KEY_REVOKE", event.action());
        assertEquals("合作结束", event.detail().get("reason"));
    }

    @Test
    void anExpiredKeyStopsWorkingOnItsOwn() {
        String tomorrow = LocalDate.now(clock).plusDays(1).toString();
        Map<String, Object> created = ok(controller.create(superAdminToken, settings("short", List.of("knowledge:read"), null, tomorrow)));
        String secret = (String) created.get("secret");
        assertEquals(true, verify(secret).get("valid"));

        clock.advance(Duration.ofDays(2));
        assertEquals(false, verify(secret).get("valid"));
        assertEquals("EXPIRED", itemFor(created.get("id")).get("status"));
    }

    @Test
    void rotatingEndsTheOldSecretAndKeepsEverythingElse() {
        Map<String, Object> created = ok(controller.create(superAdminToken, settings("rotating", List.of("knowledge:read"), null, null)));
        String old = (String) created.get("secret");

        Map<String, Object> rotated = ok(controller.rotate(superAdminToken, Map.of("keyId", created.get("id"))));
        String fresh = (String) rotated.get("secret");

        assertNotEquals(old, fresh);
        assertEquals(created.get("id"), rotated.get("id"));
        assertEquals(created.get("scopes"), rotated.get("scopes"));
        assertEquals(false, verify(old).get("valid"));
        assertEquals(true, verify(fresh).get("valid"));
        assertFalse(String.valueOf(audited).contains(fresh));
    }

    @Test
    void rescopingTakesEffectOnTheNextRequest() {
        Map<String, Object> created = ok(controller.create(superAdminToken, settings("changing", List.of("knowledge:read"), null, null)));
        String secret = (String) created.get("secret");

        ok(controller.update(superAdminToken, withKey(created.get("id"),
                settings("changing", List.of("knowledge:read", "community:write"), member.getId(), null))));
        Map<String, Object> answer = verify(secret);
        assertEquals(List.of("knowledge:read", "community:write"), answer.get("scopes"));
        assertNotNull(answer.get("token"));

        AdminAudit.Event event = audited.get(audited.size() - 1);
        assertEquals("API_KEY_UPDATE", event.action());
        assertTrue(event.summary().contains("权限"), event.summary());
    }

    @Test
    void theAccountBehindAKeyIsCheckedOnEveryRequest() {
        UserEntity bot = account("bot", "USER");
        String secret = secret(settings("bot", List.of("community:write"), bot.getId(), null));
        assertEquals(true, verify(secret).get("valid"));

        users.updateStatus(bot.getId(), "DISABLED");
        assertEquals(false, verify(secret).get("valid"), "suspending the account stops its key");

        users.updateStatus(bot.getId(), "ACTIVE");
        assertEquals(true, verify(secret).get("valid"));

        bot.setRole("ADMIN");
        users.save(bot);
        assertEquals(false, verify(secret).get("valid"), "promoting the account stops its key");
    }

    @Test
    void useIsRecordedButNotOnEveryRequest() {
        Map<String, Object> created = ok(controller.create(superAdminToken, settings("counted", List.of("knowledge:read"), null, null)));
        String secret = (String) created.get("secret");
        Long id = (Long) created.get("id");
        assertNull(keys.find(id).orElseThrow().lastUsedAt());

        verify(secret);
        var first = keys.find(id).orElseThrow().lastUsedAt();
        assertNotNull(first);

        clock.advance(Duration.ofSeconds(20));
        verify(secret);
        assertEquals(first, keys.find(id).orElseThrow().lastUsedAt(), "within the minute: not written again");

        clock.advance(Duration.ofMinutes(2));
        verify(secret);
        assertTrue(keys.find(id).orElseThrow().lastUsedAt().isAfter(first));
    }

    @Test
    void theListOffersTheScopeCatalogue() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scopes = (List<Map<String, Object>>) ok(controller.list(superAdminToken)).get("scopes");
        assertEquals(ApiKeyScopes.ALL.size(), scopes.size());
        assertEquals(List.of("knowledge:read", "knowledge:write", "community:read", "community:write"),
                scopes.stream().map(scope -> scope.get("name")).toList(),
                "the gateway maps exactly these names; changing them means changing it too");
    }

    @Test
    void aMissingKeyIdIsNotFound() {
        assertEquals("接口密钥不存在", controller.revoke(superAdminToken, Map.of()).message());
        assertEquals("接口密钥不存在", controller.rotate(superAdminToken, Map.of("keyId", "abc")).message());
        assertEquals("接口密钥不存在", controller.update(superAdminToken, Map.of("keyId", 424242)).message());
    }

    private Map<String, Object> verify(String key) {
        ApiResponse<Map<String, Object>> answer = controller.verify(INTERNAL, Map.of("key", key));
        assertEquals(0, answer.code(), answer.message());
        return answer.data();
    }

    private String secret(Map<String, Object> request) {
        return (String) ok(controller.create(superAdminToken, request)).get("secret");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemFor(Object id) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) ok(controller.list(superAdminToken)).get("items");
        return items.stream().filter(item -> item.get("id").equals(id)).findFirst().orElseThrow();
    }

    private static Map<String, Object> ok(ApiResponse<Map<String, Object>> response) {
        assertEquals(0, response.code(), response.message());
        return response.data();
    }

    private static Map<String, Object> settings(String name, List<String> scopes, Long actingUserId, String expiresOn) {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("scopes", scopes);
        request.put("actingUserId", actingUserId);
        request.put("expiresOn", expiresOn);
        return request;
    }

    private static Map<String, Object> withKey(Object id, Map<String, Object> request) {
        Map<String, Object> copy = new HashMap<>(request);
        copy.put("keyId", id);
        return copy;
    }

    private UserEntity account(String name, String role) {
        UserEntity user = new UserEntity();
        user.setUsername(name + "-" + System.nanoTime());
        user.setPasswordHash("unused");
        user.setNickname(name);
        user.setStatus("ACTIVE");
        user.setRole(role);
        return users.save(user);
    }
}
