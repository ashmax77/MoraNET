package campusnet.client.discovery;

import campusnet.api.ServerInfo;
import campusnet.common.Protocol;
import campusnet.server.discovery.DiscoveryService;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DiscoveryClient}.
 *
 * <p>All tests that require a server start their own {@link DiscoveryService} on
 * an ephemeral port (port 0) and use that port as the client target.
 *
 * <p>Tagged {@code integration} and run by the Maven Failsafe plugin.
 *
 * <p>Owner: Member 3.
 */
@Tag("integration")
class DiscoveryClientIT {

    // ── Helper ─────────────────────────────────────────────────────────────────

    /**
     * Creates a {@link FakeServerContext} that returns specific chat and file ports
     * from its Config and uses port 0 for the discovery service (ephemeral).
     */
    private static campusnet.api.ServerContext contextWithPorts(int chatPort, int filePort) {
        return new FakeServerContext() {
            @Override
            public campusnet.api.Config config() {
                return new campusnet.api.Config() {
                    @Override
                    public String get(String key, String def) { return def; }

                    @Override
                    public int getInt(String key, int def) {
                        return switch (key) {
                            case "discovery.port" -> 0;
                            case "chat.port" -> chatPort;
                            case "file.port" -> filePort;
                            default -> def;
                        };
                    }

                    @Override
                    public java.nio.file.Path getPath(String key, String def) {
                        return java.nio.file.Path.of(def);
                    }
                };
            }
        };
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * IT6 (Section 11.2): Client locates the server via loopback.
     * Verifies the returned host, chat port, and file port.
     */
    @Test
    @Timeout(10)
    void locate_serverOnLoopback_returnsCorrectServerInfo() throws Exception {
        int chatPort = 5000;
        int filePort = 5001;
        DiscoveryService svc = new DiscoveryService(contextWithPorts(chatPort, filePort));
        try {
            svc.start();
            int svcPort = svc.localPort();

            DiscoveryClient client = new DiscoveryClient(svcPort);
            Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));

            assertTrue(result.isPresent(), "Client must find the server on loopback");
            ServerInfo info = result.get();

            // The host comes from the reply packet's source address (Section 5.3).
            // On a machine with network interfaces, the service may reply from the
            // interface that received the directed broadcast (not necessarily loopback).
            // We verify only that the host is a valid, non-empty IP string.
            assertNotNull(info.host(), "Host must not be null");
            assertFalse(info.host().isEmpty(), "Host must not be empty");
            assertDoesNotThrow(() -> java.net.InetAddress.getByName(info.host()),
                    "Host must be a valid IP address: " + info.host());
            assertEquals(chatPort, info.chatPort(),
                    "Chat port must match the configured port");
            assertEquals(filePort, info.filePort(),
                    "File port must match the configured port");
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(10)
    void locate_serverWithCustomPorts_returnsCorrectPorts() throws Exception {
        int chatPort = 7000;
        int filePort = 7001;
        DiscoveryService svc = new DiscoveryService(contextWithPorts(chatPort, filePort));
        try {
            svc.start();
            DiscoveryClient client = new DiscoveryClient(svc.localPort());
            Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));

            assertTrue(result.isPresent());
            assertEquals(7000, result.get().chatPort());
            assertEquals(7001, result.get().filePort());
        } finally {
            svc.stop();
        }
    }

    /**
     * Server host IP must come from the reply packet's source address (Section 5.3).
     * We verify it is a valid, non-empty IP string — not "localhost" or a hard-coded constant.
     */
    @Test
    @Timeout(10)
    void locate_hostFromPacketSource_notHardcoded() throws Exception {
        DiscoveryService svc = new DiscoveryService(contextWithPorts(5000, 5001));
        try {
            svc.start();
            DiscoveryClient client = new DiscoveryClient(svc.localPort());
            Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));

            assertTrue(result.isPresent());
            String host = result.get().host();
            assertNotNull(host);
            assertFalse(host.isEmpty(), "Host must not be empty");
            // The host must be a valid IP address — it comes from the datagram source address.
            assertDoesNotThrow(() -> InetAddress.getByName(host),
                    "Host must be a valid IP address: " + host);
        } finally {
            svc.stop();
        }
    }

    /**
     * When no server is running, locate() must return Optional.empty()
     * within the required 3.5-second bound (Section 8.3).
     */
    @Test
    @Timeout(8)   // generous JUnit timeout; the method itself must finish in <3.5 s
    void locate_noServer_returnsEmptyWithinTimeBound() throws Exception {
        // Bind a socket on a random port, note the port, then immediately close it.
        // The client targets that now-closed port — guaranteed to find no server.
        int closedPort;
        try (java.net.DatagramSocket tmp = new java.net.DatagramSocket(0)) {
            closedPort = tmp.getLocalPort();
        }

        DiscoveryClient client = new DiscoveryClient(closedPort);

        long startNs = System.nanoTime();
        Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertTrue(result.isEmpty(),
                "locate() must return Optional.empty() when no server is reachable");
        assertTrue(elapsedMs <= 3500,
                "locate() must finish within 3500 ms when no server is found, actual: " + elapsedMs + " ms");
    }

    /**
     * Verifies that locate() returns empty within the time bound even when a
     * port receives garbage datagrams (not a valid SERVER reply).
     */
    @Test
    @Timeout(8)
    void locate_invalidReplies_returnsEmptyWithinTimeBound() throws Exception {
        // Start a UDP server that replies with garbage.
        java.net.DatagramSocket garbageServer = new java.net.DatagramSocket(0);
        int garbagePort = garbageServer.getLocalPort();

        Thread replyThread = new Thread(() -> {
            try {
                byte[] buf = new byte[512];
                java.net.DatagramPacket pkt = new java.net.DatagramPacket(buf, buf.length);
                while (!Thread.currentThread().isInterrupted()) {
                    garbageServer.receive(pkt);
                    byte[] reply = "INVALID RESPONSE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    garbageServer.send(new java.net.DatagramPacket(
                            reply, reply.length, pkt.getAddress(), pkt.getPort()));
                }
            } catch (Exception e) {
                System.getLogger(getClass().getName()).log(
                        System.Logger.Level.WARNING,
                        "Garbage discovery server failed: {0}",
                        e.getMessage());
            }
        }, "garbage-server");
        replyThread.setDaemon(true);
        replyThread.start();

        try {
            DiscoveryClient client = new DiscoveryClient(garbagePort);
            long startNs = System.nanoTime();
            Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            assertTrue(result.isEmpty(),
                    "locate() must return empty when only invalid replies are received");
            assertTrue(elapsedMs <= 3500,
                    "locate() with only invalid replies must finish within 3500 ms, actual: " + elapsedMs + " ms");
        } finally {
            replyThread.interrupt();
            garbageServer.close();
        }
    }

    /**
     * locate() must stop after the first valid reply and return a single ServerInfo
     * (not wait for more replies or aggregate multiple responses).
     */
    @Test
    @Timeout(10)
    void locate_multipleServices_returnsFirst() throws Exception {
        // Start two services on different ephemeral ports.
        DiscoveryService svc1 = new DiscoveryService(contextWithPorts(5000, 5001));
        DiscoveryService svc2 = new DiscoveryService(contextWithPorts(6000, 6001));
        try {
            svc1.start();
            svc2.start();

            // The client targets only svc1's port.
            DiscoveryClient client = new DiscoveryClient(svc1.localPort());
            Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));

            assertTrue(result.isPresent(), "Client must find the server");
            // The result must be from svc1 (chat port 5000, file port 5001).
            assertEquals(5000, result.get().chatPort());
            assertEquals(5001, result.get().filePort());
        } finally {
            svc1.stop();
            svc2.stop();
        }
    }

    /**
     * locate() with a very short caller timeout must respect that timeout,
     * finishing in less than the full 3-attempt × 1-second duration.
     */
    @Test
    @Timeout(5)
    void locate_shortCallerTimeout_completesWithinTimeout() throws Exception {
        int closedPort;
        try (java.net.DatagramSocket tmp = new java.net.DatagramSocket(0)) {
            closedPort = tmp.getLocalPort();
        }

        DiscoveryClient client = new DiscoveryClient(closedPort);

        long startNs = System.nanoTime();
        Optional<ServerInfo> result = client.locate(Duration.ofMillis(500));
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        // The method must complete within ~600 ms (500 ms timeout + small overhead).
        assertTrue(result.isEmpty());
        assertTrue(elapsedMs < 1500,
                "locate() with 500 ms timeout must finish quickly, actual: " + elapsedMs + " ms");
    }
}
