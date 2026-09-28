package com.aiknowledge.user.security;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.store.AuditLogStore;
import com.aiknowledge.user.store.UserStore;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sets an administrator's password from the server, for when no one can do it in the app — above all the super
 * administrator's, which the app deliberately cannot reset (see PasswordResetController).
 *
 * <pre>docker compose run --rm --no-deps user-service reset-admin-password [username]</pre>
 *
 * <p>Whoever can run this already controls the server, so the command asks nothing more of them; what it guards is
 * the password itself. It is read from the terminal without echo and typed twice, or — for scripts — as the first
 * line of standard input; never from the command line, where it would stay in shell history and show in the
 * process list. It is checked by the same rules as everywhere else, is never printed or logged, and every session
 * the account had is ended. The change is written to the admin action log, since it bypasses the app — in the same
 * transaction as the password itself, so there is never a reset without its entry, or an entry without its reset.
 */
public final class AdminPasswordCommand {
    public static final String NAME = "reset-admin-password";
    static final String DEFAULT_USERNAME = "admin";
    static final int OK = 0;
    static final int REFUSED = 1;
    static final int FAILED = 2;
    /** The password was changed and logged, but the account's sessions could not be ended. */
    static final int SESSIONS_NOT_ENDED = 3;
    /** The admin action log needs an actor; this one is the server, not a person. */
    static final long SERVER_ACTOR_ID = 0L;

    /** Where the new password comes from. */
    public interface PasswordSource {
        /** The password, or null when none was given; {@code confirmed} is false when two entries differed. */
        Entry read() throws IOException;

        record Entry(char[] password, boolean confirmed) {
        }
    }

    private final UserStore users;
    private final PasswordEncoder encoder;
    private final TokenRevocations revocations;
    private final AuditLogStore audit;
    private final Consumer<Runnable> inTransaction;

    public AdminPasswordCommand(UserStore users, PasswordEncoder encoder, TokenRevocations revocations, AuditLogStore audit,
                                Consumer<Runnable> inTransaction) {
        this.users = users;
        this.encoder = encoder;
        this.revocations = revocations;
        this.audit = audit;
        this.inTransaction = inTransaction;
    }

    /** Starts the service's data layer without its web server, runs the command, and returns the exit code. */
    public static int launch(Class<?> application, String[] args) {
        String username = args.length > 1 && !args[1].isBlank() ? args[1].trim() : DEFAULT_USERNAME;
        if (args.length > 2) {
            System.err.println("用法：" + NAME + " [用户名]。密码不能写在命令行上，运行后按提示输入。");
            return REFUSED;
        }
        SpringApplication app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            AdminPasswordCommand command = new AdminPasswordCommand(context.getBean(UserStore.class),
                    context.getBean(PasswordEncoder.class), context.getBean(TokenRevocations.class),
                    context.getBean(AuditLogStore.class), work -> transaction.executeWithoutResult(status -> work.run()));
            return command.run(username, systemSource(), System.out);
        } catch (RuntimeException error) {
            System.err.println("重置失败，未做任何修改：" + error.getClass().getSimpleName());
            return FAILED;
        }
    }

    public int run(String username, PasswordSource source, PrintStream out) {
        UserEntity user = users.findByUsername(username).orElse(null);
        if (user == null) {
            out.println("没有名为 " + username + " 的账号。");
            return REFUSED;
        }
        if (!"ADMIN".equals(role(user))) {
            out.println(username + " 不是管理员账号。普通会员请在登录页申请找回密码，由管理员签发重置码。");
            return REFUSED;
        }
        PasswordSource.Entry entry;
        try {
            entry = source.read();
        } catch (IOException error) {
            out.println("无法读取密码。");
            return FAILED;
        }
        char[] typed = entry == null ? null : entry.password();
        try {
            if (typed == null || typed.length == 0) {
                out.println("没有输入密码，未做任何修改。");
                return REFUSED;
            }
            if (!entry.confirmed()) {
                out.println("两次输入的密码不一致，未做任何修改。");
                return REFUSED;
            }
            String password = new String(typed);
            String problem = PasswordRules.problem(password, user.getUsername());
            if (problem != null) {
                out.println("密码不符合要求：" + ApiResponse.fail(problem).message() + "。未做任何修改。");
                return REFUSED;
            }
            String hash = encoder.encode(password);
            AuditEntry record = new AuditEntry(Instant.now(), SERVER_ACTOR_ID, "服务器命令", "ADMIN_PASSWORD_RESET",
                    AdminAudit.ACCOUNTS, "USER", String.valueOf(user.getId()), user.getUsername(), user.getId(),
                    "在服务器上重置管理员密码", Map.of(), "server-command", null);
            // Both or neither: a failure to log undoes the new password (and is reported by launch as a failure).
            inTransaction.accept(() -> {
                users.updatePassword(user.getId(), hash);
                audit.save(record);
            });
        } finally {
            if (typed != null) Arrays.fill(typed, '\0');
        }
        try {
            revocations.revokeUser(user.getId());
        } catch (RuntimeException error) {
            out.println("已重置 " + user.getUsername() + " 的密码并写入操作记录，但未能结束该账号已登录的会话（"
                    + error.getClass().getSimpleName() + "）。请检查 Redis 后重新运行本命令。");
            return SESSIONS_NOT_ENDED;
        }
        out.println("已重置 " + user.getUsername() + " 的密码，并结束了该账号所有已登录的会话。");
        out.println("如果该账号因多次输错被暂停登录，重启 user-service 即可解除：docker compose restart user-service");
        if (!"ACTIVE".equals(user.getStatus())) out.println("注意：该账号当前状态为 " + user.getStatus() + "，需要启用后才能登录。");
        return OK;
    }

    private static String role(UserEntity user) {
        return user.getRole() == null || user.getRole().isBlank() ? LocalAuth.roleForUsername(user.getUsername()) : user.getRole();
    }

    /** The terminal without echo, typed twice; or, with no terminal, the first line of standard input. */
    static PasswordSource systemSource() {
        return () -> {
            Console console = System.console();
            if (console != null) {
                char[] first = console.readPassword("新密码（输入时不显示）：");
                char[] second = console.readPassword("再次输入新密码：");
                boolean same = first != null && Arrays.equals(first, second);
                if (second != null) Arrays.fill(second, '\0');
                return new PasswordSource.Entry(first, same);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            return new PasswordSource.Entry(line == null ? null : line.toCharArray(), true);
        };
    }
}
