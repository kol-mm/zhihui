package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Administrators are appointed by the super administrator alone. An ordinary administrator keeps every other
 * power it had, so the rest of the moderation work is unaffected.
 */
class SuperAdminTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/super-admin-" + System.nanoTime());
    }

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final InMemoryUserStore store = new InMemoryUserStore(passwordEncoder);
    private final UserController controller = new UserController(store, passwordEncoder);

    private String superAdminAuth() {
        UserEntity admin = store.findByUsername("admin").orElseThrow();
        return "Bearer " + LocalAuth.issueToken(admin.getUsername(), admin.getId(), "ADMIN", true);
    }

    private String ordinaryAdminAuth() {
        UserEntity helper = store.findByUsername("helper").orElseGet(() -> register("helper"));
        store.updateGovernance(helper.getId(), "ADMIN", "STANDARD", true);
        return "Bearer " + LocalAuth.issueToken("helper", helper.getId(), "ADMIN", false);
    }

    private UserEntity register(String username) {
        ApiResponse<Map<String, Object>> created = controller.register(credentials(username, "password123"));
        assertEquals(0, created.code(), created.message());
        return store.findByUsername(username).orElseThrow();
    }

    /** Registration and sign-in are captcha-protected; the challenge is a sum this test can answer. */
    private java.util.Map<String, String> credentials(String username, String password) {
        var challenge = controller.captcha();
        String question = String.valueOf(challenge.data().get("question"));
        String[] parts = question.replace("= ?", "").split("\\+");
        int answer = Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
        java.util.Map<String, String> request = new java.util.HashMap<>();
        request.put("username", username);
        request.put("password", password);
        request.put("captchaId", String.valueOf(challenge.data().get("captchaId")));
        request.put("captchaAnswer", String.valueOf(answer));
        return request;
    }

    @Test
    void anOrdinaryAdministratorCannotAppointAnotherAdministrator() {
        UserEntity member = register("candidate");

        ApiResponse<Map<String, Object>> refused = controller.updateUserGovernance(ordinaryAdminAuth(),
                Map.of("userId", member.getId(), "role", "ADMIN"));

        assertNotEquals(0, refused.code());
        assertEquals("只有超级管理员可以任命或撤销管理员", refused.message());
        assertEquals("USER", store.findById(member.getId()).orElseThrow().getRole());
    }

    @Test
    void theSuperAdministratorCanAppointAndRemoveAdministrators() {
        UserEntity member = register("promotable");

        assertEquals(0, controller.updateUserGovernance(superAdminAuth(),
                Map.of("userId", member.getId(), "role", "ADMIN")).code());
        assertEquals("ADMIN", store.findById(member.getId()).orElseThrow().getRole());

        assertEquals(0, controller.updateUserGovernance(superAdminAuth(),
                Map.of("userId", member.getId(), "role", "USER")).code());
        assertEquals("USER", store.findById(member.getId()).orElseThrow().getRole());
    }

    @Test
    void anOrdinaryAdministratorKeepsEveryOtherGovernancePower() {
        UserEntity member = register("governed");

        ApiResponse<Map<String, Object>> saved = controller.updateUserGovernance(ordinaryAdminAuth(),
                Map.of("userId", member.getId(), "status", "DISABLED", "publishPolicy", "PRE_REVIEW"));

        assertEquals(0, saved.code(), saved.message());
        assertEquals("DISABLED", store.findById(member.getId()).orElseThrow().getStatus());
    }

    @Test
    void theSuperAdministratorCannotBeTouchedByAnotherAdministrator() {
        UserEntity superAdmin = store.findByUsername("admin").orElseThrow();

        ApiResponse<Map<String, Object>> refused = controller.updateUserGovernance(ordinaryAdminAuth(),
                Map.of("userId", superAdmin.getId(), "status", "DISABLED"));

        assertNotEquals(0, refused.code());
        assertEquals("超级管理员账号只能由本人修改", refused.message());
        assertEquals("ACTIVE", store.findById(superAdmin.getId()).orElseThrow().getStatus());
    }

    @Test
    void theClaimOnlyCountsOnAnAdministratorsToken() {
        String memberWithClaim = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER", true);
        String ordinaryAdmin = "Bearer " + LocalAuth.issueToken("helper", 3L, "ADMIN", false);

        assertFalse(LocalAuth.isSuperAdmin(memberWithClaim), "a member cannot become one by claiming it");
        assertFalse(LocalAuth.isSuperAdmin(ordinaryAdmin));
        assertTrue(LocalAuth.isSuperAdmin(superAdminAuth()));
        assertTrue(LocalAuth.isAdmin(superAdminAuth()), "a super administrator is still an administrator");
    }

    @Test
    void signingInAsTheSuperAdministratorIssuesATokenThatSaysSo() {
        ApiResponse<Map<String, Object>> signedIn =
                controller.login(credentials("admin", "admin123"));

        assertEquals(0, signedIn.code(), signedIn.message());
        assertTrue(LocalAuth.isSuperAdmin("Bearer " + signedIn.data().get("token")));
    }

    @Test
    void anOrdinaryMembersTokenDoesNotSaySo() {
        register("plain");
        ApiResponse<Map<String, Object>> signedIn =
                controller.login(credentials("plain", "password123"));

        assertEquals(0, signedIn.code(), signedIn.message());
        assertFalse(LocalAuth.isSuperAdmin("Bearer " + signedIn.data().get("token")));
    }
}
