package com.aiknowledge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends audit entries to the user service in the background, so an administrator's action never waits for the log.
 * An entry that still cannot be delivered after a few tries is written to this service's log in full.
 */
public class HttpAdminAudit implements AdminAudit, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(HttpAdminAudit.class);
    private static final long[] RETRY_DELAYS_MILLIS = {0, 1_000, 5_000};

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI auditUri;
    private final String internalToken;
    private final String source;
    private final ThreadPoolExecutor executor;
    private final long[] retryDelaysMillis;

    public HttpAdminAudit(ObjectMapper objectMapper, String auditUrl, String internalToken, String source) {
        this(objectMapper, auditUrl, internalToken, source, RETRY_DELAYS_MILLIS);
    }

    HttpAdminAudit(ObjectMapper objectMapper, String auditUrl, String internalToken, String source, long[] retryDelaysMillis) {
        this.retryDelaysMillis = retryDelaysMillis.clone();
        this.objectMapper = objectMapper;
        this.auditUri = URI.create(auditUrl);
        this.internalToken = internalToken;
        this.source = source;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2_000), runnable -> {
            Thread thread = new Thread(runnable, "admin-audit");
            thread.setDaemon(true);
            return thread;
        }, (runnable, pool) -> log.error("Admin audit queue is full; an entry was dropped"));
    }

    @Override
    public void record(String authorization, Event event) {
        AuditEntry entry;
        try {
            entry = AuditEntry.forRequest(authorization, event, source);
        } catch (RuntimeException failure) {
            log.error("Admin audit entry could not be prepared: {}", failure.toString());
            return;
        }
        if (entry != null) executor.execute(() -> deliver(entry));
    }

    void deliver(AuditEntry entry) {
        String body;
        try {
            body = objectMapper.writeValueAsString(entry);
        } catch (Exception failure) {
            log.error("Admin audit entry could not be serialized: {} {}", entry.action(), failure.toString());
            return;
        }
        String lastProblem = "";
        for (long delay : retryDelaysMillis) {
            try {
                if (delay > 0) Thread.sleep(delay);
                HttpRequest request = HttpRequest.newBuilder(auditUri)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Token", internalToken)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && objectMapper.readTree(response.body()).path("code").asInt(500) == 0) return;
                lastProblem = "HTTP " + response.statusCode() + " " + response.body();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastProblem = "interrupted";
                break;
            } catch (Exception failure) {
                lastProblem = failure.toString();
            }
        }
        log.error("Admin audit entry could not be delivered ({}): {}", lastProblem, body);
    }

    /** Lets queued entries go out when the service stops. */
    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.error("Admin audit entries were still queued at shutdown: {}", executor.getQueue().size());
        }
    }
}
