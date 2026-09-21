package com.aiknowledge.message.controller;

import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.store.MessageStore;
import com.aiknowledge.message.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalNotificationControllerTest {
    @Test
    void selfTriggeredNotificationIsIgnored() {
        MessageStore store = mock(MessageStore.class);
        InternalNotificationController controller = new InternalNotificationController(store, "test-token");

        var response = controller.create("test-token", Map.of(
                "userId", 1L,
                "actorUserId", 1L,
                "type", "COMMENT",
                "title", "你的帖子收到新评论",
                "content", "回复自己"
        ));

        assertEquals(0, response.code());
        assertEquals(false, response.data().get("created"));
        verify(store, never()).saveNotification(any());
    }

    @Test
    void notificationFromAnotherUserIsStored() {
        MessageStore store = mock(MessageStore.class);
        NotificationEntity saved = new NotificationEntity();
        saved.setId(10L);
        saved.setUserId(1L);
        when(store.saveNotification(any())).thenReturn(saved);
        InternalNotificationController controller = new InternalNotificationController(store, "test-token");

        var response = controller.create("test-token", Map.of(
                "userId", 1L,
                "actorUserId", 2L,
                "type", "COMMENT",
                "title", "你的帖子收到新评论",
                "content", "他人回复"
        ));

        assertEquals(0, response.code());
        assertEquals(true, response.data().get("created"));
        verify(store).saveNotification(any());
    }

    @Test
    void aLinkIsKeptOnlyForKnownTargets() {
        MessageStore store = mock(MessageStore.class);
        when(store.saveNotification(any())).thenAnswer(call -> {
            NotificationEntity entity = call.getArgument(0);
            entity.setId(11L);
            return entity;
        });
        InternalNotificationController controller = new InternalNotificationController(store, "test-token");

        controller.create("test-token", Map.of("userId", 1L, "actorUserId", 2L, "type", "REPLY",
                "title", "你的评论收到新回复", "content", "《帖子》：好", "targetType", "POST", "targetId", 5L, "anchorId", 9L));
        verify(store).saveNotification(argThat(entity -> "POST".equals(entity.getTargetType())
                && Long.valueOf(5L).equals(entity.getTargetId()) && Long.valueOf(9L).equals(entity.getAnchorId())));

        MessageStore other = mock(MessageStore.class);
        when(other.saveNotification(any())).thenAnswer(call -> {
            NotificationEntity entity = call.getArgument(0);
            entity.setId(12L);
            return entity;
        });
        InternalNotificationController unlinked = new InternalNotificationController(other, "test-token");
        unlinked.create("test-token", Map.of("userId", 1L, "actorUserId", 2L, "title", "t",
                "targetType", "https://evil.example", "targetId", 5L));
        assertEquals(java.util.Set.of("POST", "KNOWLEDGE", "CHAT", "TICKET"), NotificationEntity.TARGET_TYPES);
        unlinked.create("test-token", Map.of("userId", 1L, "actorUserId", 2L, "title", "t",
                "targetType", "POST", "targetId", "abc", "anchorId", -3));
        verify(other, org.mockito.Mockito.times(2)).saveNotification(argThat(entity -> {
            assertNull(entity.getAnchorId());
            return entity.getTargetType() == null && entity.getTargetId() == null;
        }));
    }

    @Test
    void theMemberWhoRepliedIsKept() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        InternalNotificationController controller = new InternalNotificationController(store, "test-token");

        controller.create("test-token", Map.of("userId", 5, "actorUserId", 9, "type", "COMMENT",
                "title", "你的帖子收到新评论", "content", "《帖子》：内容", "targetType", "POST", "targetId", 3));
        assertEquals(9L, store.listNotifications(5L).get(0).getActorUserId());

        // Without an actor the notification is still delivered, just unattributed.
        controller.create("test-token", Map.of("userId", 5, "type", "SYSTEM", "title", "系统公告", "content", "内容"));
        assertNull(store.listNotifications(5L).stream()
                .filter(notification -> "SYSTEM".equals(notification.getType())).findFirst().orElseThrow().getActorUserId());
    }
}
