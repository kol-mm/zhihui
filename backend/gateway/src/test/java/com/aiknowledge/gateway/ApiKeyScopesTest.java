package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKeyScopesTest {
    @Test
    void theScopeNamesMatchTheOnesUserServiceOffers() {
        // user-service's ApiKeyScopes lets a super administrator choose from exactly these; its test pins the
        // same list. A scope added on one side only would be either unenforceable or ungrantable.
        assertEquals(Set.of("knowledge:read", "knowledge:write", "community:read", "community:write"), ApiKeyScopes.NAMES);
    }

    @Test
    void readsAndWritesMapToTheirOwnScope() {
        assertEquals("knowledge:read", ApiKeyScopes.required("/knowledge/list", HttpMethod.GET));
        assertEquals("knowledge:read", ApiKeyScopes.required("/knowledge/file/12", HttpMethod.HEAD));
        assertEquals("knowledge:write", ApiKeyScopes.required("/knowledge/upload", HttpMethod.POST));
        assertEquals("knowledge:write", ApiKeyScopes.required("/knowledge/file", HttpMethod.DELETE));
        assertEquals("community:read", ApiKeyScopes.required("/post/list", HttpMethod.GET));
        assertEquals("community:read", ApiKeyScopes.required("/square/feed", HttpMethod.GET));
        assertEquals("community:read", ApiKeyScopes.required("/comment/list", HttpMethod.GET));
        assertEquals("community:write", ApiKeyScopes.required("/post/create", HttpMethod.POST));
        assertEquals("community:write", ApiKeyScopes.required("/comment/create", HttpMethod.PUT));
        assertEquals("community:write", ApiKeyScopes.required("/post/1", HttpMethod.PATCH));
    }

    @Test
    void nothingAdministrativeOrInternalIsEverOpen() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST}) {
            assertNull(ApiKeyScopes.required("/knowledge/admin/files/page", method));
            assertNull(ApiKeyScopes.required("/post/admin/posts/page", method));
            assertNull(ApiKeyScopes.required("/knowledge/ADMIN/files", method), "case does not open it");
            assertNull(ApiKeyScopes.required("/knowledge/internal/reindex", method));
            assertNull(ApiKeyScopes.required("/comment/admin", method));
        }
    }

    @Test
    void servicesNoScopeCoversStayClosed() {
        for (String path : new String[]{"/user/session", "/user/admin/api-keys", "/user/internal/api-keys/verify",
                "/message/sessions", "/notification/list", "/feedback/tickets", "/event/list", "/ai/ask",
                "/gateway/status", "/", "/knowledgebase", "/posts"}) {
            assertNull(ApiKeyScopes.required(path, HttpMethod.GET), path);
            assertNull(ApiKeyScopes.required(path, HttpMethod.POST), path);
        }
    }

    @Test
    void pathsThatCouldMeanSomethingElseAreTurnedAway() {
        // Each unsafe spelling next to the clean path it would otherwise be: the clean one is open, so the unsafe
        // one is refused because of its spelling and nothing else.
        String[][] cases = {
                {"/knowledge/../list", "/knowledge/list"},
                {"/knowledge/%2e%2e/list", "/knowledge/list"},
                {"/knowledge/%2E%2E/list", "/knowledge/list"},
                {"/knowledge/a%2fb", "/knowledge/ab"},
                {"/knowledge/a%5cb", "/knowledge/ab"},
                {"/knowledge/a\\b", "/knowledge/ab"},
                {"/knowledge/a;b", "/knowledge/ab"},
                {"/knowledge//list", "/knowledge/list"},
                {"knowledge/list", "/knowledge/list"},
        };
        for (String[] pair : cases) {
            assertEquals("knowledge:read", ApiKeyScopes.required(pair[1], HttpMethod.GET), pair[1]);
            assertNull(ApiKeyScopes.required(pair[0], HttpMethod.GET), pair[0]);
        }
    }

    @Test
    void onlyOrdinaryMethodsAreConsidered() {
        assertNull(ApiKeyScopes.required("/knowledge/list", HttpMethod.OPTIONS));
        assertNull(ApiKeyScopes.required("/knowledge/list", HttpMethod.TRACE));
        assertNull(ApiKeyScopes.required("/knowledge/list", null));
        assertNull(ApiKeyScopes.required(null, HttpMethod.GET));
    }
}
