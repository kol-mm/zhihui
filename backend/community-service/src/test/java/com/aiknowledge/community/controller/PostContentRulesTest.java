package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.community.store.InMemoryCommunityStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A post needs a title and a body on every path that creates or changes one. The composer checked this in the
 * browser, but the API took anything — an empty body, a title of spaces, a JSON null that became the text "null" —
 * and a draft published from the profile page never went through the composer at all.
 */
class PostContentRulesTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/post-rules-" + System.nanoTime());
    }

    // The in-memory store is saved to disk and reloaded, so each test counts only its own author's posts.
    private final long authorId = 1_000_000L + System.nanoTime() % 1_000_000_000L;
    private final InMemoryCommunityStore store = new InMemoryCommunityStore();
    private final CommunityController controller = new CommunityController(store,
            new com.aiknowledge.community.storage.CommunityMediaStorageService(
                    "local", "target/test-community-media", "http://127.0.0.1:9000",
                    "ai-community", "test", "test-password"));
    private final String author = "Bearer " + LocalAuth.issueToken("rules-author", authorId, "USER");

    /** What each rule refuses, as [title, content, the message the page shows]. */
    private static final Object[][] REFUSED = {
            {"", "正文", "帖子标题不能为空"},
            {"   ", "正文", "帖子标题不能为空"},
            {"　 ", "正文", "帖子标题不能为空"},
            {"​﻿", "正文", "帖子标题不能为空"},
            {null, "正文", "帖子标题不能为空"},
            {"标题", "", "帖子正文不能为空"},
            {"标题", " \n\t ", "帖子正文不能为空"},
            {"标题", "​", "帖子正文不能为空"},
            {"标题", null, "帖子正文不能为空"},
            {"字".repeat(256), "正文", "帖子标题不能超过 255 个字符"},
            {"标题", "字".repeat(20_001), "帖子正文不能超过 20000 个字符"},
    };

    private static Map<String, Object> body(Object title, Object content) {
        Map<String, Object> request = new HashMap<>();
        request.put("title", title);
        request.put("content", content);
        return request;
    }

    private int postCount() {
        return store.feed(authorId).size();
    }

    private Long idOf(ApiResponse<Map<String, Object>> response) {
        assertEquals(0, response.code(), response.message());
        return ((Number) response.data().get("id")).longValue();
    }

    @Test
    void creatingRefusesEachEmptyOrOversizedPostAndSavesNothing() {
        for (Object[] refused : REFUSED) {
            ApiResponse<Map<String, Object>> response = controller.createPost(author, body(refused[0], refused[1]));
            assertEquals(500, response.code(), "accepted " + refused[0] + " / " + refused[1]);
            assertEquals(refused[2], response.message());
        }
        assertEquals(500, controller.createPost(author, Map.of("content", "只有正文")).code());
        assertEquals(500, controller.createPost(author, Map.of("title", "只有标题")).code());
        assertEquals(0, postCount());
    }

    @Test
    void theLimitsThemselvesAreAllowedAndTheTitleIsTrimmed() {
        Long atLimit = idOf(controller.createPost(author, body("字".repeat(255), "字".repeat(20_000))));
        assertEquals(255, store.findPost(atLimit).orElseThrow().getTitle().length());

        Long trimmed = idOf(controller.createPost(author, body("  标题  ", "  正文保持原样  ")));
        assertEquals("标题", store.findPost(trimmed).orElseThrow().getTitle());
        assertEquals("  正文保持原样  ", store.findPost(trimmed).orElseThrow().getContent());
    }

    @Test
    void editingRefusesTheSameAndLeavesThePostAsItWas() {
        Long postId = idOf(controller.createPost(author, body("原标题", "原正文")));
        for (Object[] refused : REFUSED) {
            Map<String, Object> request = body(refused[0], refused[1]);
            request.put("id", postId);
            ApiResponse<Map<String, Object>> response = controller.updatePost(author, request);
            assertEquals(500, response.code(), "accepted " + refused[0] + " / " + refused[1]);
            assertEquals(refused[2], response.message());
        }
        assertEquals("原标题", store.findPost(postId).orElseThrow().getTitle());
        assertEquals("原正文", store.findPost(postId).orElseThrow().getContent());
    }

    @Test
    void anEditThatLeavesAFieldOutKeepsItRatherThanBlankingIt() {
        Long postId = idOf(controller.createPost(author, body("原标题", "原正文")));

        assertEquals(0, controller.updatePost(author, Map.of("id", postId, "title", "新标题")).code());
        assertEquals("原正文", store.findPost(postId).orElseThrow().getContent());

        assertEquals(0, controller.updatePost(author, Map.of("id", postId, "content", "新正文")).code());
        assertEquals("新标题", store.findPost(postId).orElseThrow().getTitle());
    }

    @Test
    void anEmptyDraftCanBeSavedButNotPublishedAndIsKept() {
        Long emptyBody = idOf(controller.saveDraft(author, body("只有标题的草稿", "")));
        Long emptyTitle = idOf(controller.saveDraft(author, body("", "只有正文的草稿")));
        Long nullTitle = idOf(controller.saveDraft(author, body(null, "正文")));

        // The profile page publishes a draft by its id alone, so the draft's own fields are what is checked.
        assertEquals("帖子正文不能为空", controller.publishDraft(author, Map.of("id", emptyBody)).message());
        assertEquals("帖子标题不能为空", controller.publishDraft(author, Map.of("id", emptyTitle)).message());
        assertEquals("帖子标题不能为空", controller.publishDraft(author, Map.of("id", nullTitle)).message());
        // Values sent with the request are checked too, not only the draft's.
        assertEquals("帖子正文不能为空",
                controller.publishDraft(author, Map.of("id", emptyTitle, "title", "补上标题", "content", " ")).message());
        assertEquals(0, postCount());
        assertEquals("", store.findDraft(nullTitle).orElseThrow().getTitle());
        assertEquals("只有标题的草稿", store.findDraft(emptyBody).orElseThrow().getTitle());

        // Filling in the missing part at publish time works, and only then is the draft used up.
        idOf(controller.publishDraft(author, Map.of("id", emptyBody, "content", "补上的正文")));
        assertEquals(1, postCount());
        assertEquals(java.util.Optional.empty(), store.findDraft(emptyBody));
    }

    @Test
    void aNullDraftFieldIsStoredEmptyRatherThanAsTheWordNull() {
        Long draftId = idOf(controller.saveDraft(author, body(null, null)));
        assertEquals("", store.findDraft(draftId).orElseThrow().getTitle());
        assertEquals("", store.findDraft(draftId).orElseThrow().getContent());

        Map<String, Object> update = body(null, "正文");
        update.put("id", draftId);
        assertEquals(0, controller.updateDraft(author, update).code());
        assertEquals("", store.findDraft(draftId).orElseThrow().getTitle());
        // Leaving the title out altogether still gets the placeholder it always did.
        Long untitled = idOf(controller.saveDraft(author, Map.of("content", "正文")));
        assertEquals("未命名草稿", store.findDraft(untitled).orElseThrow().getTitle());
    }
}
