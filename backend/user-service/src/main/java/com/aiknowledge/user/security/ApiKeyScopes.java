package com.aiknowledge.user.security;

import java.util.Collection;
import java.util.List;

/**
 * What an API key can be allowed to do.
 *
 * <p>The gateway enforces these — it maps each name to the paths and methods it opens (ApiKeyScopes in the
 * gateway module, which does not depend on this one). The two lists must name the same scopes; each module pins
 * the set in a test, so adding one here without the other fails a build. Anything not named is closed: no scope
 * reaches an admin or internal path, accounts, private messages, notifications, feedback or AI chat.
 */
public final class ApiKeyScopes {
    private ApiKeyScopes() {
    }

    /** write: the scope changes content, so a key holding it must act as an account the content belongs to. */
    public record Scope(String name, String label, boolean write) {
    }

    public static final List<Scope> ALL = List.of(
            new Scope("knowledge:read", "读取知识库", false),
            new Scope("knowledge:write", "上传与修改知识", true),
            new Scope("community:read", "读取社区内容", false),
            new Scope("community:write", "发帖与评论", true));

    public static boolean known(String name) {
        return ALL.stream().anyMatch(scope -> scope.name().equals(name));
    }

    public static boolean anyWrite(Collection<String> names) {
        return ALL.stream().anyMatch(scope -> scope.write() && names.contains(scope.name()));
    }

    public static String label(String name) {
        return ALL.stream().filter(scope -> scope.name().equals(name)).map(Scope::label).findFirst().orElse(name);
    }
}
