package com.aiknowledge.gateway;

import org.springframework.http.HttpMethod;

import java.util.Locale;
import java.util.Set;

/**
 * Which scope an API key needs for a request, decided from the path and method alone.
 *
 * <p>The names must match user-service's ApiKeyScopes, which lets a super administrator choose them; each module
 * pins the set in a test. Everything is closed unless a rule below opens it: accounts, private messages,
 * notifications, feedback, events and AI chat have no scope at all, and no path with an {@code admin} or
 * {@code internal} segment is ever reachable by a key, whatever it was granted.
 */
final class ApiKeyScopes {
    static final Set<String> NAMES = Set.of("knowledge:read", "knowledge:write", "community:read", "community:write");

    private static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD);
    private static final Set<HttpMethod> WRITES = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private ApiKeyScopes() {
    }

    /** The scope this request needs, or null when no key may make it at all. */
    static String required(String path, HttpMethod method) {
        if (path == null || method == null || !safe(path)) return null;
        String normalized = path.toLowerCase(Locale.ROOT);
        if (hasSegment(normalized, "admin") || hasSegment(normalized, "internal")) return null;
        boolean read = READS.contains(method);
        if (!read && !WRITES.contains(method)) return null;
        if (under(normalized, "/knowledge")) return read ? "knowledge:read" : "knowledge:write";
        if (under(normalized, "/post") || under(normalized, "/comment") || under(normalized, "/square")) {
            return read ? "community:read" : "community:write";
        }
        return null;
    }

    /**
     * Refuses the spellings a path could use to mean something other than what it says to this check: dot
     * segments, encoded dots and slashes, backslashes and matrix parameters. A key has no reason to send any of
     * them, so they are not interpreted, only turned away.
     */
    private static boolean safe(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return !(lower.contains("..") || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")
                || lower.contains("\\") || lower.contains(";") || lower.contains("//"));
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean hasSegment(String path, String segment) {
        for (String part : path.split("/")) {
            if (part.equals(segment)) return true;
        }
        return false;
    }
}
