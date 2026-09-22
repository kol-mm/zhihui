package com.aiknowledge.message.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.event.LocalEventBusService;
import com.aiknowledge.message.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A notification belongs to one member. Asking for "mine" must never answer with everybody's — the store reads
 * a null user id as "no filter", so the controller has to resolve it before asking.
 *
 * <p>The local store outlives a single test, so these assert on which notifications come back rather than on
 * how many.
 */
class NotificationScopeTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/notification-scope-" + System.nanoTime());
    }

    private final InMemoryMessageStore store = new InMemoryMessageStore();
    private final MessageController controller = new MessageController(
            store, new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events"));

    private static final long MEMBER_ID = 1L;
    private static final long ADMIN_ID = 2L;
    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", MEMBER_ID, "USER");
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", ADMIN_ID, "ADMIN");

    /** A title no other test can produce, so the assertions do not depend on what else is stored. */
    private String notify(long userId, String who) {
        String title = who + "-" + System.nanoTime();
        NotificationEntity notification = new NotificationEntity();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(title);
        notification.setType("COMMENT");
        store.saveNotification(notification);
        return title;
    }

    private List<String> titles(String authorization, Long requestedUserId) {
        ApiResponse<List<Map<String, Object>>> answer = controller.notifications(authorization, requestedUserId);
        assertEquals(0, answer.code(), answer.message());
        return answer.data().stream().map(item -> String.valueOf(item.get("title"))).toList();
    }

    @Test
    void anAdministratorAskingForTheirOwnGetsOnlyTheirOwn() {
        String mine = notify(ADMIN_ID, "admin");
        String theirs = notify(MEMBER_ID, "member");

        List<String> returned = titles(ADMIN, null);

        assertTrue(returned.contains(mine), "an administrator must see their own");
        assertFalse(returned.contains(theirs), "every notification on the platform used to come back here");
    }

    @Test
    void anAdministratorMayStillReadAnotherMembersOnRequest() {
        String mine = notify(ADMIN_ID, "admin");
        String theirs = notify(MEMBER_ID, "member");

        List<String> returned = titles(ADMIN, MEMBER_ID);

        assertTrue(returned.contains(theirs));
        assertFalse(returned.contains(mine), "asking for one member should not include the administrator's");
    }

    @Test
    void aMemberSeesOnlyTheirOwn() {
        String mine = notify(MEMBER_ID, "member");
        String administrators = notify(ADMIN_ID, "admin");

        List<String> returned = titles(MEMBER, null);

        assertTrue(returned.contains(mine));
        assertFalse(returned.contains(administrators));
    }

    @Test
    void aMemberCannotAskForSomeoneElses() {
        notify(ADMIN_ID, "admin");

        ApiResponse<List<Map<String, Object>>> refused = controller.notifications(MEMBER, ADMIN_ID);

        assertNotEquals(0, refused.code());
        assertTrue(refused.message().contains("无权访问") || refused.message().contains("denied"),
                refused.message());
    }
}
