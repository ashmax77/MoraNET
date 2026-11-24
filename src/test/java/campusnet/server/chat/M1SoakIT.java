package campusnet.server.chat;

import campusnet.testing.TestClient;
import campusnet.testing.TestServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT8: 200-client soak test.
 *
 * Verifies that the NIO selector loop handles 200 concurrent TCP clients
 * sending 100 messages each (20 000 total round-trips) without losing any
 * messages, hanging, or crashing the server.
 *
 * Uses PING/PONG because AUTH is a stub in this milestone.
 * Tagged "integration" so Failsafe runs it during mvn verify.
 */
@Tag("integration")
class M1SoakIT {

    private static final int NUM_CLIENTS     = 200;
    private static final int MSGS_PER_CLIENT = 100;
    private static final int TOTAL_EXPECTED  = NUM_CLIENTS * MSGS_PER_CLIENT;

    @Test
    @DisplayName("IT8: 200 clients × 100 PINGs each — all receive PONG, server stays alive")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void twoHundredClientsSoakTest() throws Exception {
        // Allow 250 connections so all 200 test clients fit (Acceptor default is 100).
        Properties overrides = new Properties();
        overrides.setProperty("chat.maxConnections", "250");
        TestServer server = new TestServer(overrides);
        server.start();
        int port = server.port();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch allReady    = new CountDownLatch(NUM_CLIENTS);
        CountDownLatch allDone     = new CountDownLatch(NUM_CLIENTS);

        // Thread pool: one thread per client so they all run concurrently.
        ExecutorService pool = Executors.newFixedThreadPool(NUM_CLIENTS);

        for (int i = 0; i < NUM_CLIENTS; i++) {
            pool.submit(() -> {
                boolean isReady = false;
                try (TestClient client = new TestClient("127.0.0.1", port)) {
                    // Consume WELCOME greeting
                    client.receive(10_000);
                    isReady = true;
                    allReady.countDown();
                    allReady.await(30, TimeUnit.SECONDS);     // Wait until all 200 clients are connected

                    // Send MSGS_PER_CLIENT PINGs and count the PONGs
                    for (int msg = 0; msg < MSGS_PER_CLIENT; msg++) {
                        client.send("PING");
                        String response = client.receive(10_000);
                        if ("PONG".equals(response)) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // Count uncompleted messages as failures
                    failureCount.addAndGet(MSGS_PER_CLIENT);
                } finally {
                    if (!isReady) {
                        allReady.countDown();
                    }
                    allDone.countDown();
                }
            });
        }

        // Wait for all clients to finish
        boolean finished = allDone.await(55, TimeUnit.SECONDS);
        pool.shutdownNow();
        server.stop();

        assertTrue(finished,
                "Not all clients finished within the time limit");
        assertEquals(0, failureCount.get(),
                "Some messages failed: " + failureCount.get() + " failures");
        assertEquals(TOTAL_EXPECTED, successCount.get(),
                "Expected " + TOTAL_EXPECTED + " PONGs but got " + successCount.get());
    }
}
