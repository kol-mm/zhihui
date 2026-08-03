package com.aiknowledge.message.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.message.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MessageControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/message-" + System.nanoTime());
    }

    private final MessageController controller =
            new MessageController(new InMemoryMessageStore());

    @Test
    void sentMessageCanBeListedAndCleared() {
        ApiResponse<Map<String, Object>> sent = controller.send(Map.of(
                "sessionId", 7L,
                "senderId", 1L,
                "content", "hello"
        ));
        assertEquals(0, sent.code());

        ApiResponse<List<Map<String, Object>>> messages = controller.list(7L);
        assertEquals(0, messages.code());
        assertEquals(1, messages.data().size());
        assertEquals("hello", messages.data().get(0).get("content"));

        ApiResponse<Map<String, Object>> cleared = controller.clear(Map.of("sessionId", 7L));
        assertEquals(0, cleared.code());
        assertEquals(1, cleared.data().get("removed"));
    }

    @Test
    void faqsAndNotificationsHaveLocalSeedData() {
        ApiResponse<List<Map<String, Object>>> faqs = controller.faqs();
        assertEquals(0, faqs.code());
        assertFalse(faqs.data().isEmpty());

        ApiResponse<List<Map<String, Object>>> notifications = controller.notifications(1L);
        assertEquals(0, notifications.code());
        assertFalse(notifications.data().isEmpty());
    }

    @Test
    void feedbackTicketCanBeCreatedAndListed() {
        ApiResponse<Map<String, Object>> created = controller.createTicket(Map.of(
                "userId", 1L,
                "type", "BUG",
                "content", "button is not clickable"
        ));
        assertEquals(0, created.code());
        assertEquals("PENDING", created.data().get("status"));

        ApiResponse<List<Map<String, Object>>> tickets = controller.tickets(1L);
        assertEquals(0, tickets.code());
        assertFalse(tickets.data().isEmpty());
        assertEquals("button is not clickable", tickets.data().get(0).get("content"));
    }
}
