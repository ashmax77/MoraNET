package campusnet.server.discovery;

import campusnet.api.ServerContext;
import campusnet.common.Protocol;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DiscoveryService}.
 *
 * <p>All tests use ephemeral port 0 so there are no port conflicts.
 * Each test starts and stops its own service instance.
 *
 * <p>Tagged {@code integration} and run by the Maven Failsafe plugin.
 *
 * <p>Owner: Member 3.
 */
@Tag("integration")
class DiscoveryServiceIT {

    // ── Helper ─────────────────────────────────────────────────────────────────

    /**
     * Builds a {@link FakeServerContext} that returns the given chat and file ports
     * from its Config via the standard config keys.
     */
    private static ServerContext contextWithPorts(int chatPort, int filePort) {
        // FakeServerContext uses PropertiesConfig.empty() which returns defaults.
        // We need a context whose config returns specific ports — wrap the fake.
        return new FakeServerContext() {
            @Override
            public campusnet.api.Config config() {
                return new campusnet.api.Config() {
                    @Override
                    public String get(String key, String def) { return def; }

                    @Override
                    public int getInt(String key, int def) {
                        return switch (key) {
                            case "discovery.port" -> 0;    // ephemeral
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

    /**
     * Sends a raw UDP datagram to the service on loopback and receives the reply.
     *
     * @param servicePort the ephemeral port of the running DiscoveryService
     * @param request     the text to send
     * @return the reply text, or {@code null} if no reply within 1 second
     */
    private static String sendAndReceive(int servicePort, String request) throws Exception {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(1000);
            InetAddress loopback = InetAddress.getLoopbackAddress();
            byte[] req = request.getBytes(StandardCharsets.UTF_8);
            sock.send(new DatagramPacket(req, req.length, loopback, servicePort));

            byte[] buf = new byte[513];
            DatagramPacket replyPkt = new DatagramPacket(buf, buf.length);
            sock.receive(replyPkt);
            return new String(replyPkt.getData(), 0, replyPkt.getLength(), StandardCharsets.UTF_8);
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * IT6 (Section 11.2): Service starts on an ephemeral port, the loopback client
     * discovers it and receives a correct SERVER reply.
     */
    @Test
    @Timeout(5)
    void start_bindsEphemeralPort_nonZero() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            assertTrue(svc.localPort() > 0,
                    "localPort() must return a positive port after start()");
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(5)
    void discover_loopback_returnsCorrectServerPorts() throws Exception {
        int chatPort = 5000;
        int filePort = 5001;
        ServerContext ctx = contextWithPorts(chatPort, filePort);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            String reply = sendAndReceive(svc.localPort(), "DISCOVER campusnet/1");
            assertNotNull(reply, "Service must reply to a valid DISCOVER request");
            assertEquals("SERVER " + chatPort + " " + filePort, reply.trim());
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(5)
    void discover_customPorts_returnedInReply() throws Exception {
        int chatPort = 7000;
        int filePort = 7001;
        ServerContext ctx = contextWithPorts(chatPort, filePort);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            String reply = sendAndReceive(svc.localPort(), "DISCOVER campusnet/1");
            assertNotNull(reply);
            assertEquals("SERVER 7000 7001", reply.trim());
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(5)
    void discover_invalidDatagram_serviceIgnoresAndContinues() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            int svcPort = svc.localPort();

            // Send garbage — service must silently ignore it.
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(300);
                byte[] garbage = "GARBAGE_PAYLOAD_12345".getBytes(StandardCharsets.UTF_8);
                InetAddress lo = InetAddress.getLoopbackAddress();
                sock.send(new DatagramPacket(garbage, garbage.length, lo, svcPort));
                // Expect no reply — a receive here should time out.
                DatagramPacket reply = new DatagramPacket(new byte[512], 512);
                assertThrows(java.net.SocketTimeoutException.class,
                        () -> sock.receive(reply),
                        "Service must not reply to invalid datagrams");
            }

            // Now send a valid request — service must still be running.
            String goodReply = sendAndReceive(svcPort, "DISCOVER campusnet/1");
            assertNotNull(goodReply);
            assertTrue(goodReply.trim().startsWith("SERVER "),
                    "Service must still respond correctly after ignoring garbage");
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(5)
    void discover_wrongVersion_serviceIgnores() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            int svcPort = svc.localPort();

            // Wrong protocol version.
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(300);
                byte[] req = "DISCOVER campusnet/2".getBytes(StandardCharsets.UTF_8);
                InetAddress lo = InetAddress.getLoopbackAddress();
                sock.send(new DatagramPacket(req, req.length, lo, svcPort));
                DatagramPacket reply = new DatagramPacket(new byte[512], 512);
                assertThrows(java.net.SocketTimeoutException.class,
                        () -> sock.receive(reply),
                        "Service must ignore DISCOVER with wrong version");
            }
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(5)
    void discover_oversizedDatagram_serviceIgnores() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            int svcPort = svc.localPort();

            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(300);
                // 513-byte datagram exceeds the 512-byte limit.
                byte[] bigPayload = new byte[513];
                java.util.Arrays.fill(bigPayload, (byte) 'A');
                InetAddress lo = InetAddress.getLoopbackAddress();
                sock.send(new DatagramPacket(bigPayload, bigPayload.length, lo, svcPort));
                DatagramPacket reply = new DatagramPacket(new byte[512], 512);
                assertThrows(java.net.SocketTimeoutException.class,
                        () -> sock.receive(reply),
                        "Service must ignore datagrams exceeding 512 bytes");
            }
        } finally {
            svc.stop();
        }
    }

    @Test
    @Timeout(5)
    void stop_idempotent_noException() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        svc.start();
        // Call stop() twice — must not throw.
        assertDoesNotThrow(() -> {
            svc.stop();
            svc.stop();
        }, "stop() must be idempotent");
    }

    @Test
    @Timeout(5)
    void stop_channelClosed_receiveLoopTerminates() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        svc.start();

        // Record that the service is alive (can respond).
        String reply = sendAndReceive(svc.localPort(), "DISCOVER campusnet/1");
        assertNotNull(reply);

        svc.stop();

        // After stop(), the port must be unresponsive.
        int stoppedPort = svc.localPort();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(300);
            byte[] req = "DISCOVER campusnet/1".getBytes(StandardCharsets.UTF_8);
            InetAddress lo = InetAddress.getLoopbackAddress();
            sock.send(new DatagramPacket(req, req.length, lo, stoppedPort));
            DatagramPacket replyPkt = new DatagramPacket(new byte[512], 512);
            assertThrows(java.net.SocketTimeoutException.class,
                    () -> sock.receive(replyPkt),
                    "After stop(), no reply must be received");
        }
    }

    @Test
    @Timeout(5)
    void localPort_beforeStart_returnsZero() {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        assertEquals(0, svc.localPort(),
                "localPort() before start() must return 0 (the initial value)");
    }

    /**
     * Rate-limiting integration test: sends 15 valid DISCOVER packets in rapid succession
     * and verifies the service stops responding after 10 within the 1-second window.
     *
     * <p>This test uses actual sockets but deliberately avoids arbitrary sleeps.
     * It runs all 15 sends as fast as the OS allows, so they fall within one window,
     * then counts only replies received within a 300 ms collection window.
     */
    @Test
    @Timeout(10)
    void rateLimiter_caps10RepliesPerSecond() throws Exception {
        ServerContext ctx = contextWithPorts(5000, 5001);
        DiscoveryService svc = new DiscoveryService(ctx);
        try {
            svc.start();
            int svcPort = svc.localPort();
            int totalSent = 15;

            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(300);
                InetAddress lo = InetAddress.getLoopbackAddress();
                byte[] req = "DISCOVER campusnet/1".getBytes(StandardCharsets.UTF_8);

                // Send 15 packets as fast as possible.
                for (int i = 0; i < totalSent; i++) {
                    sock.send(new DatagramPacket(req, req.length, lo, svcPort));
                }

                // Collect replies within a 300 ms window.
                int replies = 0;
                DatagramPacket replyPkt = new DatagramPacket(new byte[512], 512);
                try {
                    while (true) {
                        sock.receive(replyPkt);
                        replies++;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // Collection window expired — normal termination.
                }

                // We expect at most 10 replies (rate limit) and at least 1
                // (the service definitely answered some).
                assertTrue(replies >= 1,
                        "Service must reply to at least one valid DISCOVER request");
                assertTrue(replies <= 10,
                        "Service must not reply more than 10 times per second per source: got " + replies);
            }
        } finally {
            svc.stop();
        }
    }
}
