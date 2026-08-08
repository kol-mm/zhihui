package com.aiknowledge.message.controller;

import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.store.MessageStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
}
