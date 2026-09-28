package com.aiknowledge.user.security;

import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.store.AuditLogStore;
import com.aiknowledge.user.store.InMemoryAuditLogStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPasswordCommandTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/admin-password-" + System.nanoTime());
    }

    private static final String NEW_PASSWORD = "Recovered-2026";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final InMemoryUserStore users = new InMemoryUserStore(encoder);
    private final List<Long> revokedUsers = new ArrayList<>();
    private final List<AuditEntry> audited = new ArrayList<>();
    private final TokenRevocations revocations = new TokenRevocations() {
        @Override
        public void revokeToken(String tokenId, long expiresAtEpochSecond) {
        }

        @Override
        public void revokeUser(long userId) {
            revokedUsers.add(userId);
        }

        @Override
        public boolean isRevoked(com.aiknowledge.common.LocalAuth.TokenInfo token) {
            return false;
        }
    };
    private final AuditLogStore audit = new InMemoryAuditLogStore() {
        @Override
        public synchronized StoredEntry save(AuditEntry entry) {
            audited.add(entry);
            return super.save(entry);
        }
    };
    /**
     * Stands in for the database transaction: what the work changed in the user store is put back if it throws —
     * enough to show the command does both writes inside it.
     */
    private final java.util.function.Consumer<Runnable> transaction = work -> {
        java.util.Map<Long, String> before = new java.util.HashMap<>();
        users.listUsers().forEach(user -> before.put(user.getId(), user.getPasswordHash()));
        try {
            work.run();
        } catch (RuntimeException error) {
            before.forEach(users::updatePassword);
            throw error;
        }
    };
    private final AdminPasswordCommand command = new AdminPasswordCommand(users, encoder, revocations, audit, transaction);
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);

    private UserEntity account(String role, boolean superAdmin) {
        UserEntity user = new UserEntity();
        user.setUsername("staffmember" + (System.nanoTime() % 1_000_000));
        user.setPasswordHash(encoder.encode("old-pass1"));
        user.setNickname("管理员");
        user.setStatus("ACTIVE");
        user.setRole(role);
        user.setSuperAdmin(superAdmin);
        return users.save(user);
    }

    private static AdminPasswordCommand.PasswordSource typed(String password, boolean confirmed) {
        return () -> new AdminPasswordCommand.PasswordSource.Entry(password == null ? null : password.toCharArray(), confirmed);
    }

    private String hashOf(UserEntity user) {
        return users.findById(user.getId()).orElseThrow().getPasswordHash();
    }

    @Test
    void theSuperAdministratorsPasswordIsSetAndEverySessionEnded() {
        UserEntity admin = account("ADMIN", true);

        int exit = command.run(admin.getUsername(), typed(NEW_PASSWORD, true), out);

        assertEquals(AdminPasswordCommand.OK, exit);
        assertTrue(encoder.matches(NEW_PASSWORD, hashOf(admin)));
        assertEquals(List.of(admin.getId()), revokedUsers);
    }

    /** It bypasses the app, so the admin action log is told — and never told the password. */
    @Test
    void theChangeIsLoggedWithoutThePassword() {
        UserEntity admin = account("ADMIN", true);

        command.run(admin.getUsername(), typed(NEW_PASSWORD, true), out);

        assertEquals(1, audited.size());
        AuditEntry entry = audited.get(0);
        assertEquals("ADMIN_PASSWORD_RESET", entry.action());
        assertEquals("server-command", entry.source());
        // admin_audit_log.actor_id is NOT NULL; the in-memory store does not check, so this test does.
        assertEquals(AdminPasswordCommand.SERVER_ACTOR_ID, entry.actorId());
        assertEquals(admin.getId(), entry.subjectUserId());
        String everything = entry.toString() + printed.toString(StandardCharsets.UTF_8);
        assertFalse(everything.contains(NEW_PASSWORD), everything);
        assertFalse(everything.contains(hashOf(admin)), everything);
    }

    /** There is never a reset without its log entry: if the entry cannot be written, the password stays as it was. */
    @Test
    void aResetThatCannotBeLoggedDoesNotHappen() {
        UserEntity admin = account("ADMIN", true);
        String before = hashOf(admin);
        AuditLogStore failing = new InMemoryAuditLogStore() {
            @Override
            public synchronized StoredEntry save(AuditEntry entry) {
                throw new IllegalStateException("audit table unavailable");
            }
        };
        AdminPasswordCommand unlogged = new AdminPasswordCommand(users, encoder, revocations, failing, transaction);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> unlogged.run(admin.getUsername(), typed(NEW_PASSWORD, true), out));

        assertEquals(before, hashOf(admin));
        assertTrue(revokedUsers.isEmpty());
    }

    @Test
    void sessionsThatCannotBeEndedAreReportedNotHidden() {
        UserEntity admin = account("ADMIN", true);
        TokenRevocations down = new TokenRevocations() {
            @Override
            public void revokeToken(String tokenId, long expiresAtEpochSecond) {
            }

            @Override
            public void revokeUser(long userId) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public boolean isRevoked(com.aiknowledge.common.LocalAuth.TokenInfo token) {
                return false;
            }
        };

        int exit = new AdminPasswordCommand(users, encoder, down, audit, transaction)
                .run(admin.getUsername(), typed(NEW_PASSWORD, true), out);

        assertEquals(AdminPasswordCommand.SESSIONS_NOT_ENDED, exit);
        assertTrue(encoder.matches(NEW_PASSWORD, hashOf(admin)));
        assertEquals(1, audited.size());
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("未能结束该账号已登录的会话"));
    }

    @Test
    void membersAreNotResetFromTheServer() {
        UserEntity member = account("USER", false);
        String before = hashOf(member);

        assertEquals(AdminPasswordCommand.REFUSED, command.run(member.getUsername(), typed(NEW_PASSWORD, true), out));

        assertEquals(before, hashOf(member));
        assertTrue(revokedUsers.isEmpty() && audited.isEmpty());
    }

    @Test
    void nothingChangesWhenTheInputIsNotAcceptable() {
        UserEntity admin = account("ADMIN", true);
        String before = hashOf(admin);

        assertEquals(AdminPasswordCommand.REFUSED, command.run("nobody-here", typed(NEW_PASSWORD, true), out));
        assertEquals(AdminPasswordCommand.REFUSED, command.run(admin.getUsername(), typed(null, true), out));
        assertEquals(AdminPasswordCommand.REFUSED, command.run(admin.getUsername(), typed("", true), out));
        assertEquals(AdminPasswordCommand.REFUSED, command.run(admin.getUsername(), typed(NEW_PASSWORD, false), out));
        assertEquals(AdminPasswordCommand.REFUSED, command.run(admin.getUsername(), typed("short1", true), out));
        assertEquals(AdminPasswordCommand.REFUSED, command.run(admin.getUsername(), typed("onlyletters", true), out));
        assertEquals(AdminPasswordCommand.REFUSED, command.run(admin.getUsername(), typed(admin.getUsername(), true), out));

        assertEquals(before, hashOf(admin));
        assertTrue(revokedUsers.isEmpty() && audited.isEmpty());
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("两次输入的密码不一致"));
    }

    @Test
    void theTypedPasswordIsWipedFromMemoryAfterUse() {
        UserEntity admin = account("ADMIN", true);
        char[] typedChars = NEW_PASSWORD.toCharArray();

        command.run(admin.getUsername(), () -> new AdminPasswordCommand.PasswordSource.Entry(typedChars, true), out);

        assertArrayEquals(new char[NEW_PASSWORD.length()], typedChars);
    }

    @Test
    void thePasswordIsNeverTakenFromTheCommandLine() {
        assertEquals(AdminPasswordCommand.REFUSED,
                AdminPasswordCommand.launch(Object.class, new String[]{AdminPasswordCommand.NAME, "admin", NEW_PASSWORD}));
    }
}
