package campusnet.server.chat;

import campusnet.testing.TestClient;
import campusnet.testing.TestServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for slow-consumer backpressure enforcement (M1).
 *
 * <p>Verifies that the NIO EventLoop periodic sweep detects a client whose
 * outbound queue exceeds {@code chat.maxOutboundBytes}, terminates the slow
 * connection, and leaves all well-behaved clients unaffected.
 */
@Tag("integration")
class M1SlowConsumerIT {

    private static final int BACKPRESSURE_BYTES = 32 * 1024; // 32 KB
    private static final int FLOOD_COUNT = 600;
    private static final String PAYLOAD = "A".repeat(2000); // 2 KB per message -> 1.2 MB total

    @Test
    @DisplayName("IT-SC: slow consumer is dropped when outbox exceeds maxOutboundBytes; fast client unaffected")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void slowConsumerDroppedAndIsolated() throws Exception {
        Properties overrides = new Properties();
        overrides.setProperty("chat.maxOutboundBytes", String.valueOf(BACKPRESSURE_BYTES));
        overrides.setProperty("chat.maxConnections", "10");

        TestServer server = new TestServer(overrides);
        server.start();
        int port = server.port();

        // 1. Slow client: connects, authenticates as bob, then completely stops reading.
        SocketChannel slowCh = SocketChannel.open();
        slowCh.configureBlocking(true);
        slowCh.connect(new InetSocketAddress("127.0.0.1", port));

        InputStream slowIn = slowCh.socket().getInputStream();
        OutputStream slowOut = slowCh.socket().getOutputStream();

        drainFrame(slowIn); // consume WELCOME
        sendFrame(slowOut, "AUTH bob pw2");
        drainFrame(slowIn); // consume "OK AUTH bob LECTURER"

        // 2. Fast client: connects, authenticates as alice.
        try (TestClient fast = new TestClient("127.0.0.1", port)) {
            fast.receive(2_000); // WELCOME
            fast.send("AUTH alice pw1");
            String authReply = fast.receive(2_000);
            assertEquals("OK AUTH alice STUDENT", authReply);

            // 3. Alice broadcasts messages. Alice immediately drains her own echoed
            // broadcast frames, keeping her server outbox at 0 bytes.
            // Meanwhile, Bob never reads, so the server outbox for Bob accumulates
            // ~100 KB (> 64 KB backpressure threshold).
            for (int i = 0; i < FLOOD_COUNT; i++) {
                fast.send("MSG " + PAYLOAD);
                String echo = fast.receive(2_000);
                assertTrue(echo.startsWith("MSG alice:") || echo.equals("LEAVE bob"),
                        "Expected MSG echo or LEAVE notification, got: " + echo);
            }

            // 4. Verify that Bob's connection is closed by the server's backpressure sweep.
            boolean slowDropped = waitForEof(slowCh, 6_000);
            assertTrue(slowDropped, "Slow consumer 'bob' should have been disconnected by backpressure sweep");

            // 5. Fast client remains healthy and responsive. Drain any in-flight
            // broadcasts until PONG is received.
            fast.send("PING");
            String response = "";
            while (!"PONG".equals(response)) {
                response = fast.receive(2_000);
            }
            assertEquals("PONG", response);

            // Verify Bob is no longer in the active user list.
            fast.send("LIST");
            String listResponse = "";
            while (!listResponse.startsWith("USERS")) {
                listResponse = fast.receive(2_000);
            }
            assertEquals("USERS alice", listResponse);
        } finally {
            try { slowCh.close(); } catch (Exception ignored) {}
            server.stop();
        }
    }

    private static void drainFrame(InputStream in) throws Exception {
        byte[] lenBuf = new byte[4];
        int r = 0;
        while (r < 4) {
            int n = in.read(lenBuf, r, 4 - r);
            if (n == -1) throw new IllegalStateException("Unexpected EOF while reading frame length");
            r += n;
        }
        int len = ByteBuffer.wrap(lenBuf).getInt();
        byte[] payload = new byte[len];
        int p = 0;
        while (p < len) {
            int n = in.read(payload, p, len - p);
            if (n == -1) throw new IllegalStateException("Unexpected EOF while reading frame payload");
            p += n;
        }
    }

    private static void sendFrame(OutputStream out, String message) throws Exception {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + payload.length);
        buf.putInt(payload.length);
        buf.put(payload);
        out.write(buf.array());
        out.flush();
    }

    private static boolean waitForEof(SocketChannel ch, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            ch.configureBlocking(true);
            ch.socket().setSoTimeout(300);
            InputStream is = ch.socket().getInputStream();
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                try {
                    int n = is.read(buf);
                    if (n == -1) return true; // EOF reached
                } catch (java.net.SocketTimeoutException e) {
                    // Check deadline and retry
                } catch (Exception e) {
                    return true; // Socket closed or reset
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }
}
