package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.ApiKeyScopes;
import com.aiknowledge.user.security.InternalAuth;
import com.aiknowledge.user.store.ApiKeyStore;
import com.aiknowledge.user.store.ApiKeyStore.ApiKey;
import com.aiknowledge.user.store.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Platform API keys: credentials an outside system presents to call this platform's API. Only a super
 * administrator can see or change them.
 *
 * <p>A key is 32 random bytes. Only its SHA-256 digest is kept — a random secret that long needs no slow hash —
 * so the full key is shown once, when it is made or rotated, and never again; the list shows its first eleven
 * characters so a person can tell keys apart.
 *
 * <p>The gateway asks {@code /user/internal/api-keys/verify} on every request a key makes and enforces the
 * scopes. It is never cached there, so a revoked, expired or rescoped key changes behaviour on its next request.
 * A key acting for an account is handed on as a token that lives for a minute and is always an ordinary member's.
 */
@RestController
@RequestMapping("/user")
public class ApiKeyController {
    static final String KEY_PREFIX = "zk_";
    static final int SECRET_BYTES = 32;
    /** "zk_" and eight characters of the secret: enough to tell keys apart, far too few to guess the rest. */
    static final int DISPLAY_PREFIX_LENGTH = 11;
    static final int NAME_MAX = 64;
    static final int KEY_MAX_LENGTH = 128;
    static final long DELEGATED_TOKEN_SECONDS = 60;

    private final ApiKeyStore store;
    private final UserStore userStore;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private AdminAudit audit = AdminAudit.NONE;

    @Autowired
    public ApiKeyController(ApiKeyStore store, UserStore userStore) {
        this(store, userStore, Clock.systemDefaultZone());
    }

    ApiKeyController(ApiKeyStore store, UserStore userStore, Clock clock) {
        this.store = store;
        this.userStore = userStore;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAdminAudit(AdminAudit audit) {
        this.audit = audit == null ? AdminAudit.NONE : audit;
    }

    @GetMapping("/admin/api-keys")
    public ApiResponse<Map<String, Object>> list(@RequestHeader(name = "Authorization", required = false) String authorization) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireSuperAdmin(authorization);
        if (denied != null) return denied;
        List<ApiKey> keys = store.list();
        Map<Long, UserEntity> people = people(keys.stream()
                .flatMap(key -> Stream.of(key.actingUserId(), key.createdBy(), key.revokedBy())));
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", keys.stream().map(key -> view(key, people, now)).toList());
        result.put("scopes", ApiKeyScopes.ALL.stream()
                .map(scope -> Map.of("name", scope.name(), "label", scope.label(), "write", scope.write())).toList());
        return ApiResponse.ok(result);
    }

    @PostMapping("/admin/api-keys")
    public ApiResponse<Map<String, Object>> create(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                   @RequestBody Map<String, Object> request) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireSuperAdmin(authorization);
        if (denied != null) return denied;
        Settings settings = Settings.read(request, clock);
        if (settings.problem() != null) return ApiResponse.fail(settings.problem());
        String problem = actingProblem(settings.actingUserId(), settings.scopes());
        if (problem != null) return ApiResponse.fail(problem);

        String secret = newSecret();
        ApiKey key = store.create(settings.name(), displayPrefix(secret), digest(secret), settings.scopes(),
                settings.actingUserId(), LocalAuth.userId(authorization), settings.expiresAt());
        // The secret goes back in this response and nowhere else: not the log, not the audit record.
        audit.record(authorization, AdminAudit.Event.of("API_KEY_CREATE", AdminAudit.SYSTEM, "API_KEY", key.id(),
                        label(key), key.actingUserId(), "创建接口密钥「" + key.name() + "」")
                .with("scopes", key.scopes()));
        Map<String, Object> result = view(key, people(Stream.of(key.actingUserId(), key.createdBy())), LocalDateTime.now(clock));
        result.put("secret", secret);
        return ApiResponse.ok(result);
    }

    @PostMapping("/admin/api-keys/update")
    public ApiResponse<Map<String, Object>> update(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                   @RequestBody Map<String, Object> request) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireSuperAdmin(authorization);
        if (denied != null) return denied;
        ApiKey existing = requested(request);
        if (existing == null) return ApiResponse.fail("接口密钥不存在");
        if (existing.revoked()) return ApiResponse.fail("已撤销的接口密钥不能修改");
        Settings settings = Settings.read(request, clock);
        if (settings.problem() != null) return ApiResponse.fail(settings.problem());
        String problem = actingProblem(settings.actingUserId(), settings.scopes());
        if (problem != null) return ApiResponse.fail(problem);

        ApiKey updated = store.update(existing.id(), settings.name(), settings.scopes(), settings.actingUserId(),
                settings.expiresAt()).orElse(null);
        if (updated == null) return ApiResponse.fail("已撤销的接口密钥不能修改");
        Map<Long, UserEntity> people = people(Stream.of(existing.actingUserId(), updated.actingUserId(), updated.createdBy()));
        AdminAudit.Changes changes = new AdminAudit.Changes()
                .add("name", "名称", existing.name(), updated.name())
                .add("scopes", "权限", scopeLabels(existing.scopes()), scopeLabels(updated.scopes()))
                .add("actingUser", "代为操作的账号", personLabel(people, existing.actingUserId()), personLabel(people, updated.actingUserId()))
                .add("expiresAt", "有效期至", existing.expiresAt(), updated.expiresAt());
        if (!changes.isEmpty()) {
            audit.record(authorization, AdminAudit.Event.of("API_KEY_UPDATE", AdminAudit.SYSTEM, "API_KEY", updated.id(),
                    label(updated), updated.actingUserId(), "修改接口密钥「" + updated.name() + "」的" + changes.labels())
                    .withChanges(changes));
        }
        return ApiResponse.ok(view(updated, people, LocalDateTime.now(clock)));
    }

    @PostMapping("/admin/api-keys/rotate")
    public ApiResponse<Map<String, Object>> rotate(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                   @RequestBody Map<String, Object> request) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireSuperAdmin(authorization);
        if (denied != null) return denied;
        ApiKey existing = requested(request);
        if (existing == null) return ApiResponse.fail("接口密钥不存在");
        String secret = newSecret();
        ApiKey rotated = store.rotate(existing.id(), displayPrefix(secret), digest(secret)).orElse(null);
        if (rotated == null) return ApiResponse.fail("已撤销的接口密钥不能轮换");
        audit.record(authorization, AdminAudit.Event.of("API_KEY_ROTATE", AdminAudit.SYSTEM, "API_KEY", rotated.id(),
                label(rotated), rotated.actingUserId(), "轮换接口密钥「" + rotated.name() + "」，旧密钥立即失效")
                .with("previousPrefix", existing.prefix()));
        Map<String, Object> result = view(rotated, people(Stream.of(rotated.actingUserId(), rotated.createdBy())), LocalDateTime.now(clock));
        result.put("secret", secret);
        return ApiResponse.ok(result);
    }

    @PostMapping("/admin/api-keys/revoke")
    public ApiResponse<Map<String, Object>> revoke(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                   @RequestBody Map<String, Object> request) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireSuperAdmin(authorization);
        if (denied != null) return denied;
        ApiKey existing = requested(request);
        if (existing == null) return ApiResponse.fail("接口密钥不存在");
        String reason = text(request.get("reason"));
        if (reason.length() > 200) reason = reason.substring(0, 200);
        ApiKey revoked = store.revoke(existing.id(), LocalAuth.userId(authorization)).orElse(null);
        if (revoked == null) return ApiResponse.fail("接口密钥已经撤销");
        AdminAudit.Event event = AdminAudit.Event.of("API_KEY_REVOKE", AdminAudit.SYSTEM, "API_KEY", revoked.id(),
                label(revoked), revoked.actingUserId(), "撤销接口密钥「" + revoked.name() + "」");
        audit.record(authorization, reason.isEmpty() ? event : event.with("reason", reason));
        return ApiResponse.ok(view(revoked, people(Stream.of(revoked.actingUserId(), revoked.createdBy(), revoked.revokedBy())),
                LocalDateTime.now(clock)));
    }

    /**
     * The gateway's question on every request a key makes. Answers {valid:false} for anything that is not a live
     * key, and never says which part failed to the caller — only to the gateway's log, through {@code reason}.
     */
    @PostMapping("/internal/api-keys/verify")
    public ApiResponse<Map<String, Object>> verify(@RequestHeader(name = "X-Internal-Token", required = false) String token,
                                                   @RequestBody Map<String, Object> request) {
        if (!InternalAuth.accepts(token)) return ApiResponse.fail("internal token is required");
        String presented = text(request.get("key"));
        if (!presented.startsWith(KEY_PREFIX) || presented.length() > KEY_MAX_LENGTH) return invalid("malformed");
        ApiKey key = store.findByHash(digest(presented)).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (key == null) return invalid("unknown");
        if (key.revoked()) return invalid("revoked");
        if (key.expired(now)) return invalid("expired");

        String delegated = null;
        if (key.actingUserId() != null) {
            UserEntity user = userStore.findById(key.actingUserId()).orElse(null);
            // The account is checked again on every request: suspending it, or making it an administrator,
            // must stop the key at once rather than when someone next edits it.
            if (actingAccountProblem(user) != null) return invalid("acting account unavailable");
            delegated = LocalAuth.issueDelegatedToken(user.getUsername(), user.getId(), key.id(), DELEGATED_TOKEN_SECONDS);
        } else if (ApiKeyScopes.anyWrite(key.scopes())) {
            return invalid("write scope without an acting account");
        }
        store.touch(key.id(), now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("keyId", key.id());
        result.put("scopes", key.scopes());
        result.put("token", delegated);
        return ApiResponse.ok(result);
    }

    /** The key a request names, or null when it names none that exists. */
    private ApiKey requested(Map<String, Object> request) {
        Long keyId = number(request.get("keyId"));
        return keyId == null ? null : store.find(keyId).orElse(null);
    }

    private static ApiResponse<Map<String, Object>> invalid(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false);
        result.put("reason", reason);
        return ApiResponse.ok(result);
    }

    /** The name, scopes, acting account and expiry a request asks for, or what is wrong with them. */
    record Settings(String name, List<String> scopes, Long actingUserId, LocalDateTime expiresAt, String problem) {
        static Settings read(Map<String, Object> request, Clock clock) {
            String name = text(request.get("name"));
            if (name.isEmpty()) return failed("请填写接口密钥的名称");
            if (name.length() > NAME_MAX) return failed("名称不能超过 " + NAME_MAX + " 个字符");

            List<String> scopes = new ArrayList<>(new LinkedHashSet<>(list(request.get("scopes"))));
            if (scopes.isEmpty()) return failed("请至少选择一项权限");
            for (String scope : scopes) {
                if (!ApiKeyScopes.known(scope)) return failed("未知的权限：" + scope);
            }
            // Stored in the catalogue's order, so the same set always reads the same way.
            scopes = ApiKeyScopes.ALL.stream().map(ApiKeyScopes.Scope::name).filter(scopes::contains).toList();

            Long actingUserId = request.get("actingUserId") == null || text(request.get("actingUserId")).isEmpty()
                    ? null : number(request.get("actingUserId"));

            LocalDateTime expiresAt = null;
            String expiry = text(request.get("expiresOn"));
            if (!expiry.isEmpty()) {
                try {
                    // Valid through the whole of the chosen day.
                    expiresAt = LocalDate.parse(expiry).atTime(23, 59, 59);
                } catch (DateTimeParseException invalidDate) {
                    return failed("有效期格式应为 yyyy-MM-dd");
                }
                if (!expiresAt.isAfter(LocalDateTime.now(clock))) return failed("有效期不能早于今天");
            }
            return new Settings(name, scopes, actingUserId, expiresAt, null);
        }

        private static Settings failed(String problem) {
            return new Settings(null, List.of(), null, null, problem);
        }
    }

    /** Why this account cannot back these scopes; null when it can. */
    private String actingProblem(Long actingUserId, List<String> scopes) {
        if (actingUserId == null) {
            return ApiKeyScopes.anyWrite(scopes) ? "带写权限的接口密钥必须指定一个代为操作的账号，写入的内容归属该账号" : null;
        }
        return actingAccountProblem(userStore.findById(actingUserId).orElse(null));
    }

    private static String actingAccountProblem(UserEntity user) {
        if (user == null) return "代为操作的账号不存在";
        if (!"ACTIVE".equals(user.getStatus())) return "代为操作的账号未处于正常状态";
        // Delegated tokens are always an ordinary member's, but a key standing in for an administrator would
        // still act as that person's identity — their drafts, their own records — so it is refused outright.
        if ("ADMIN".equals(user.getRole())) return "接口密钥不能代表管理员账号操作";
        return null;
    }

    private Map<String, Object> view(ApiKey key, Map<Long, UserEntity> people, LocalDateTime now) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", key.id());
        view.put("name", key.name());
        view.put("prefix", key.prefix());
        view.put("scopes", key.scopes());
        view.put("status", key.status(now));
        view.put("actingUser", person(people, key.actingUserId()));
        view.put("createdBy", person(people, key.createdBy()));
        view.put("revokedBy", person(people, key.revokedBy()));
        view.put("createdAt", key.createdAt());
        view.put("updatedAt", key.updatedAt());
        view.put("expiresAt", key.expiresAt());
        view.put("lastUsedAt", key.lastUsedAt());
        view.put("revokedAt", key.revokedAt());
        return view;
    }

    private Map<Long, UserEntity> people(Stream<Long> ids) {
        List<Long> wanted = ids.filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) return Map.of();
        return userStore.findByIds(wanted).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (first, second) -> first));
    }

    private static Map<String, Object> person(Map<Long, UserEntity> people, Long id) {
        if (id == null) return null;
        UserEntity user = people.get(id);
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("id", id);
        person.put("username", user == null ? "" : user.getUsername());
        person.put("nickname", user == null ? "" : user.getNickname());
        person.put("status", user == null ? "" : user.getStatus());
        person.put("role", user == null ? "" : user.getRole());
        return person;
    }

    private static String personLabel(Map<Long, UserEntity> people, Long id) {
        return id == null ? "无" : UserController.userLabel(people.get(id), id);
    }

    private static String scopeLabels(List<String> scopes) {
        return String.join("、", scopes.stream().map(ApiKeyScopes::label).toList());
    }

    private static String label(ApiKey key) {
        return key.name() + "（" + key.prefix() + "…）";
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String displayPrefix(String secret) {
        return secret.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    static String digest(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Long number(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static List<String> list(Object value) {
        if (value instanceof List<?> items) return items.stream().map(ApiKeyController::text).filter(item -> !item.isEmpty()).toList();
        String single = text(value);
        return single.isEmpty() ? List.of() : List.of(single.split("\\s*,\\s*"));
    }
}
