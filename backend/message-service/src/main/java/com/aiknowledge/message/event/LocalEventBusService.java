package com.aiknowledge.message.event;

import com.aiknowledge.common.LocalJsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LocalEventBusService {
    private final Path storePath = LocalJsonStore.dataFile("events.json");
    private final AtomicLong ids = new AtomicLong(5000);
    private final List<EventRecord> events = new CopyOnWriteArrayList<>();
    private final String mode;
    private final String rabbitHost;
    private final int rabbitPort;
    private final String exchange;

    public LocalEventBusService(
            @Value("${message.event.mode:local}") String mode,
            @Value("${message.event.rabbitmq.host:127.0.0.1}") String rabbitHost,
            @Value("${message.event.rabbitmq.port:5672}") int rabbitPort,
            @Value("${message.event.rabbitmq.exchange:ai-knowledge.events}") String exchange
    ) {
        this.mode = mode;
        this.rabbitHost = rabbitHost;
        this.rabbitPort = rabbitPort;
        this.exchange = exchange;
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.events != null) {
            events.addAll(state.events);
            ids.set(events.stream().map(EventRecord::getId).filter(id -> id != null).mapToLong(Long::longValue).max().orElse(5000L));
        }
    }

    public EventRecord publish(String type, String aggregateId, Map<String, Object> payload) {
        EventRecord event = new EventRecord();
        event.setId(ids.incrementAndGet());
        event.setType(type);
        event.setAggregateId(aggregateId);
        event.setPayload(payload == null ? Map.of() : new LinkedHashMap<>(payload));
        event.setStatus("local".equalsIgnoreCase(mode) ? "LOCAL_STORED" : "RABBITMQ_READY");
        event.setCreatedAt(LocalDateTime.now());
        events.add(event);
        persist();
        return event;
    }

    public List<EventRecord> list(Integer limit) {
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return events.stream().sorted(Comparator.comparing(EventRecord::getCreatedAt).reversed()).limit(size).toList();
    }

    public Map<String, Object> status() {
        return Map.of(
                "mode", mode,
                "storedEvents", events.size(),
                "rabbitHost", rabbitHost,
                "rabbitPort", rabbitPort,
                "exchange", exchange,
                "rabbitReady", !"local".equalsIgnoreCase(mode)
        );
    }

    private void persist() {
        State state = new State();
        state.events = new ArrayList<>(events);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<EventRecord> events = new ArrayList<>();
    }

    public static class EventRecord {
        private Long id;
        private String type;
        private String aggregateId;
        private Map<String, Object> payload = new LinkedHashMap<>();
        private String status;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getAggregateId() { return aggregateId; }
        public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
        public Map<String, Object> getPayload() { return payload; }
        public void setPayload(Map<String, Object> payload) { this.payload = payload; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}
