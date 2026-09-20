package com.aiknowledge.user.store;

import com.aiknowledge.user.store.ProfileAuditStore.ChangeQuery;
import com.aiknowledge.user.store.ProfileAuditStore.ProfileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The review queue's own rules, independent of the controller. */
class ProfileAuditStoreTest {
    // A fresh directory per test: the local store persists, and these tests count the whole queue.
    {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/profile-store-" + System.nanoTime());
    }

    private final ProfileAuditStore store = new InMemoryProfileAuditStore();

    @Test
    void aMemberHasOneOpenChangeAtATime() {
        ProfileChange first = store.submit(7L, "昵称一", "签名一", "原昵称", "原签名");
        ProfileChange second = store.submit(7L, "昵称二", "签名二", "原昵称", "原签名");

        assertEquals(first.id(), second.id(), "the same row is reused");
        assertEquals(first.createdAt(), second.createdAt(), "and keeps its place in the queue");
        assertEquals("昵称二", store.findOpen(7L).orElseThrow().nickname());
        assertEquals(1, store.countPending());
    }

    @Test
    void decidingClosesTheChangeAndFreesTheMember() {
        ProfileChange change = store.submit(8L, "昵称", "签名", "原昵称", "原签名");
        ProfileChange decided = store.resolve(change.id(), ProfileAuditStore.REJECTED, "不合规", 2L).orElseThrow();

        assertEquals(ProfileAuditStore.REJECTED, decided.status());
        assertEquals("不合规", decided.reason());
        assertEquals(2L, decided.reviewerId());
        assertTrue(store.findOpen(8L).isEmpty());
        assertTrue(store.resolve(change.id(), ProfileAuditStore.APPROVED, null, 2L).isEmpty(), "decided only once");
        assertEquals(0, store.countPending());

        // A new submission starts a new row and becomes what the member's page reports.
        ProfileChange again = store.submit(8L, "再试", "签名", "原昵称", "原签名");
        assertEquals(again.id(), store.findLatest(8L).orElseThrow().id());
        assertTrue(again.id() > change.id());
    }

    @Test
    void theQueueIsNewestFirstWithAKeysetCursor() {
        List<Long> ids = List.of(11L, 12L, 13L, 14L).stream()
                .map(userId -> store.submit(userId, "昵称" + userId, null, "原", null).id()).toList();

        List<ProfileChange> first = store.pageChanges(new ChangeQuery(null, null, null, 2));
        assertEquals(List.of(ids.get(3), ids.get(2)), first.stream().map(ProfileChange::id).toList());
        List<ProfileChange> second = store.pageChanges(new ChangeQuery(null, null, first.get(1).id(), 2));
        assertEquals(List.of(ids.get(1), ids.get(0)), second.stream().map(ProfileChange::id).toList());
        assertEquals(4, store.countChanges(new ChangeQuery(null, null, null, Integer.MAX_VALUE)));
    }

    @Test
    void filtersMatchStateNicknameAndIds() {
        ProfileChange kept = store.submit(21L, "保留昵称", null, "原", null);
        ProfileChange closed = store.submit(22L, "已处理昵称", null, "原", null);
        store.resolve(closed.id(), ProfileAuditStore.APPROVED, null, 2L);

        assertEquals(List.of(kept.id()), ids(store.pageChanges(new ChangeQuery(null, "PENDING", null, 20))));
        assertEquals(List.of(closed.id()), ids(store.pageChanges(new ChangeQuery(null, "APPROVED", null, 20))));
        assertEquals(List.of(kept.id()), ids(store.pageChanges(new ChangeQuery("保留", null, null, 20))));
        assertEquals(List.of(kept.id()), ids(store.pageChanges(new ChangeQuery("21", null, null, 20))), "the member id");
        assertEquals(List.of(kept.id()), ids(store.pageChanges(new ChangeQuery(String.valueOf(kept.id()), null, null, 20))));
        assertEquals(List.of(), ids(store.pageChanges(new ChangeQuery("没有这个", null, null, 20))));
    }

    private static List<Long> ids(List<ProfileChange> changes) {
        return changes.stream().map(ProfileChange::id).toList();
    }
}
