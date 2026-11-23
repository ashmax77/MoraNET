package campusnet.server.chat;

import campusnet.testing.TestClient;
import campusnet.testing.TestServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class M1LongSoakIT {

    @Test
    @EnabledIfSystemProperty(named = "m1.long.soak", matches = "true")
    @Timeout(value = 610, unit = TimeUnit.SECONDS)
    void tenMinuteTwoHundredClientSoak() throws Exception {
        Properties overrides = new Properties();
        overrides.setProperty("chat.maxConnections", "250");
        TestServer server = new TestServer(overrides);
        server.start();

        int port = server.port();
        int clientCount = 200;
        int pingCount = 100;
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(clientCount);
        CountDownLatch finished = new CountDownLatch(clientCount);
        ExecutorService pool = Executors.newFixedThreadPool(clientCount);

        long deadline = System.nanoTime()
                + TimeUnit.MINUTES.toNanos(10);

        for (int i = 0; i < clientCount; i++) {
            pool.submit(() -> {
                try (TestClient client = new TestClient("127.0.0.1", port)) {
                    client.receive(5_000);
                    ready.countDown();
                    ready.await();
                    for (int p = 0; p < pingCount && System.nanoTime() < deadline; p++) {
                        client.send("PING");
                        String response = client.receive(5_000);
                        if (!"PONG".equals(response)) {
                            failures.incrementAndGet();
                        }
                        Thread.sleep(20);
                    }
                    while (System.nanoTime() < deadline) {
                        client.send("PING");
                        String response = client.receive(5_000);
                        if (!"PONG".equals(response)) {
                            failures.incrementAndGet();
                        }
                        Thread.sleep(100);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }

        boolean completed = finished.await(605, TimeUnit.SECONDS);
        pool.shutdownNow();
        server.stop();

        assertTrue(completed, "Not all 200 clients remained connected for 10 minutes");
        assertEquals(0, failures.get());
    }
}
