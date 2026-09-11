package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private WebhookSink sink;
    private Database database;
    private NotificationRepository repository;
    private Metrics metrics;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        sink = new WebhookSink();
        database = TestSupport.openDatabase(temporaryDirectory, "notifications.db");
        repository = new NotificationRepository(database.connections());
        metrics = new Metrics();
    }

    @AfterEach
    void tearDown() {
        sink.close();
        database.close();
    }

    private NotificationService service(boolean enabled, String url, long reminderSeconds) {
        return new NotificationService(repository, MAPPER, metrics, enabled, url, 2, 3, 10, reminderSeconds);
    }

    private AlertTransition transition(AlertTransition.Type type) {
        AlertRecord alert = new AlertRecord("cpu-high@web-01", "HIGH_CPU", "web-01", "cpu is high",
                Instant.parse("2026-09-11T10:00:00Z"));
        return new AlertTransition(alert, type);
    }

    private void awaitDeliveries(int expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && sink.requestCount() < expected) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void deliversAVersionedPayloadWhenAnAlertOpens() throws Exception {
        NotificationService service = service(true, sink.url(), 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(1);
        service.shutdown();

        assertEquals(1, sink.requestCount());
        JsonNode payload = MAPPER.readTree(sink.lastBody());
        assertEquals("eventwatch.notification.v1", payload.path("schema_version").asText());
        assertEquals("alert.opened", payload.path("event_type").asText());
        assertEquals("cpu-high@web-01", payload.path("alert").path("alert_key").asText());
        assertEquals("HIGH_CPU", payload.path("alert").path("alert_type").asText());
        assertEquals("OPEN", payload.path("alert").path("status").asText());
        assertEquals("application/json", sink.lastContentType());

        List<NotificationRecord> history = repository.findByAlertKey("cpu-high@web-01", 10);
        assertEquals(1, history.size());
        assertEquals("DELIVERED", history.get(0).deliveryStatus());
        assertEquals(200, history.get(0).httpStatus());
        assertTrue(metrics.render(0, 0).contains("eventwatch_notifications_total{status=\"DELIVERED\"} 1"));
    }

    @Test
    void everyLifecycleChangeDelivers() throws Exception {
        NotificationService service = service(true, sink.url(), 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(1);
        service.handle(transition(AlertTransition.Type.ACKNOWLEDGED));
        awaitDeliveries(2);
        service.handle(transition(AlertTransition.Type.RESOLVED));
        awaitDeliveries(3);
        service.handle(transition(AlertTransition.Type.REOPENED));
        awaitDeliveries(4);
        service.shutdown();

        assertEquals(4, sink.requestCount());
    }

    @Test
    void repeatOccurrencesAreSuppressedByTheCooldown() throws Exception {
        NotificationService service = service(true, sink.url(), 3600);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(1);
        service.handle(transition(AlertTransition.Type.OCCURRENCE));
        service.handle(transition(AlertTransition.Type.OCCURRENCE));
        service.shutdown();

        assertEquals(1, sink.requestCount(), "occurrences inside the cooldown must not deliver");
        assertTrue(metrics.render(0, 0).contains("eventwatch_notifications_total{status=\"SUPPRESSED\"} 2"));
    }

    @Test
    void aZeroReminderDisablesOccurrenceNotificationsEntirely() throws Exception {
        NotificationService service = service(true, sink.url(), 0);
        service.handle(transition(AlertTransition.Type.OCCURRENCE));
        service.shutdown();
        assertEquals(0, sink.requestCount());
    }

    @Test
    void anOccurrenceDeliversOnceTheCooldownHasPassed() throws Exception {
        NotificationService service = service(true, sink.url(), 1);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(1);
        Thread.sleep(1100);
        service.handle(transition(AlertTransition.Type.OCCURRENCE));
        awaitDeliveries(2);
        service.shutdown();

        assertEquals(2, sink.requestCount());
        assertEquals("alert.reminder", MAPPER.readTree(sink.lastBody()).path("event_type").asText());
    }

    @Test
    void aServerErrorIsRetriedUpToTheAttemptLimit() throws Exception {
        sink.respondWith(500);
        NotificationService service = service(true, sink.url(), 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(3);
        service.shutdown();

        assertEquals(3, sink.requestCount(), "max attempts is three");
        List<NotificationRecord> history = repository.findByAlertKey("cpu-high@web-01", 10);
        assertEquals(3, history.size());
        assertEquals(3, history.get(0).attemptNumber());
        assertTrue(history.stream().allMatch(record -> "FAILED".equals(record.deliveryStatus())));
    }

    @Test
    void aClientErrorStopsImmediately() throws Exception {
        sink.respondWith(400);
        NotificationService service = service(true, sink.url(), 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(1);
        Thread.sleep(200);
        service.shutdown();

        assertEquals(1, sink.requestCount(), "a permanent rejection must not be retried");
        assertEquals(400, repository.findByAlertKey("cpu-high@web-01", 10).get(0).httpStatus());
    }

    @Test
    void rateLimitingIsTreatedAsTemporary() throws Exception {
        sink.respondWith(429);
        NotificationService service = service(true, sink.url(), 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        awaitDeliveries(3);
        service.shutdown();

        assertEquals(3, sink.requestCount(), "429 is retryable like a server error");
    }

    @Test
    void anUnreachableWebhookIsRecordedWithAReadableReason() throws Exception {
        // Port 1 is reserved and refuses connections, so the failure carries no message.
        NotificationService service = service(true, "http://127.0.0.1:1/hook", 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        Thread.sleep(500);
        service.shutdown();

        List<NotificationRecord> history = repository.findByAlertKey("cpu-high@web-01", 10);
        assertTrue(history.size() >= 1);
        assertNotNull(history.get(0).errorMessage());
        assertFalseContains(history.get(0).errorMessage(), "null");
    }

    @Test
    void disabledNotificationsAreANoOp() throws Exception {
        NotificationService service = service(false, sink.url(), 900);
        service.handle(transition(AlertTransition.Type.OPENED));
        Thread.sleep(100);
        service.shutdown();

        assertEquals(0, sink.requestCount());
        assertTrue(repository.findByAlertKey("cpu-high@web-01", 10).isEmpty());
    }

    @Test
    void aMissingOrMalformedWebhookUrlIsANoOp() throws Exception {
        for (String url : new String[] {"", "   ", "not-a-url", "ftp://example.com/hook"}) {
            NotificationService service = service(true, url, 900);
            service.handle(transition(AlertTransition.Type.OPENED));
            Thread.sleep(50);
            service.shutdown();
        }
        assertEquals(0, sink.requestCount());
        assertTrue(repository.findByAlertKey("cpu-high@web-01", 10).isEmpty());
    }

    private void assertFalseContains(String value, String unwanted) {
        assertTrue(!value.endsWith(": " + unwanted),
                "delivery failure should name the cause, got: " + value);
    }

    /** A minimal HTTP endpoint that records what the notification service sent it. */
    private static final class WebhookSink implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final java.util.concurrent.atomic.AtomicInteger requests =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile String lastBody = "";
        private volatile String lastContentType = "";
        private volatile int responseStatus = 200;

        WebhookSink() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
            server.createContext("/hook", exchange -> {
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
                requests.incrementAndGet();
                exchange.sendResponseHeaders(responseStatus, -1);
                exchange.close();
            });
            server.start();
        }

        void respondWith(int status) {
            responseStatus = status;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        int requestCount() {
            return requests.get();
        }

        String lastBody() {
            return lastBody;
        }

        String lastContentType() {
            return lastContentType;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
