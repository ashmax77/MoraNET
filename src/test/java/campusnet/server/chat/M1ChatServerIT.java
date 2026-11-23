package campusnet.server.chat;

import campusnet.testing.TestClient;
import campusnet.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the M1 NIO chat server (I1 milestone).
 *
 * These tests use a real TCP stack: {@link TestServer} binds on an ephemeral
 * port; {@link TestClient} connects and exchanges length-prefixed frames.
 *
 * Tagged "integration" so the Failsafe plugin picks them up separately.
 * Run with: mvn verify   (or  mvn failsafe:integration-test)
 */
@Tag("integration")
class M1ChatServerIT {

    private TestServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -------------------------------------------------------------------------
    // IT-1: WELCOME greeting is sent on connect
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-1: Server sends WELCOME campusnet/1 on connect")
    void connectReceivesWelcome() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            String welcome = client.receive(2_000);
            assertEquals("WELCOME campusnet/1", welcome);
        }
    }

    // -------------------------------------------------------------------------
    // IT-2: PING/PONG works before authentication
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-2: PING receives PONG without authentication")
    void pingPong() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);          // consume WELCOME
            client.send("PING");
            String response = client.receive(2_000);
            assertEquals("PONG", response);
        }
    }

    // -------------------------------------------------------------------------
    // IT-3: Multiple clients can connect independently
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-3: Two clients each receive independent WELCOME frames")
    void twoClientsConnectIndependently() throws Exception {
        try (TestClient c1 = new TestClient("127.0.0.1", server.port());
             TestClient c2 = new TestClient("127.0.0.1", server.port())) {

            String w1 = c1.receive(2_000);
            String w2 = c2.receive(2_000);
            assertEquals("WELCOME campusnet/1", w1);
            assertEquals("WELCOME campusnet/1", w2);
        }
    }

    // -------------------------------------------------------------------------
    // IT-4: QUIT sends OK BYE and closes the connection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-4: QUIT receives OK BYE then server closes the connection")
    void quitClosesConnection() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);          // WELCOME
            client.send("QUIT");
            String bye = client.receive(2_000);
            assertEquals("OK BYE", bye);
            // After OK BYE the server closes; the next receive should fail.
            assertThrows(Exception.class, () -> client.receive(1_000));
        }
    }

    // -------------------------------------------------------------------------
    // IT-5: AUTH_REQUIRED returned when issuing MSG without login
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-5: MSG without AUTH returns ERR AUTH_REQUIRED")
    void msgRequiresAuth() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);          // WELCOME
            client.send("MSG hello");
            String response = client.receive(2_000);
            assertEquals("ERR AUTH_REQUIRED", response);
        }
    }

    // -------------------------------------------------------------------------
    // IT-6: LIST without AUTH returns ERR AUTH_REQUIRED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-6: LIST without AUTH returns ERR AUTH_REQUIRED")
    void listRequiresAuth() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);          // WELCOME
            client.send("LIST");
            String response = client.receive(2_000);
            assertEquals("ERR AUTH_REQUIRED", response);
        }
    }

    // -------------------------------------------------------------------------
    // IT-7: Unknown command returns ERR UNKNOWN_COMMAND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-7: Unknown command returns ERR UNKNOWN_COMMAND")
    void unknownCommand() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);          // WELCOME
            client.send("FOOBAR");
            String response = client.receive(2_000);
            assertEquals("ERR UNKNOWN_COMMAND", response);
        }
    }

    // -------------------------------------------------------------------------
    // IT-8: Oversized frame closes only the offending connection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-8: Oversized frame closes sender; other clients unaffected")
    void oversizedFrameIsolated() throws Exception {
        try (TestClient good = new TestClient("127.0.0.1", server.port());
             java.net.Socket rawBad = new java.net.Socket("127.0.0.1", server.port())) {

            good.receive(2_000);    // WELCOME for good client

            // Consume the WELCOME sent to the bad raw socket.
            java.io.InputStream in = rawBad.getInputStream();
            byte[] welcomeHeader = new byte[4];
            int totalRead = 0;
            while (totalRead < 4) totalRead += in.read(welcomeHeader, totalRead, 4 - totalRead);
            int welcomeLen = java.nio.ByteBuffer.wrap(welcomeHeader).getInt();
            in.readNBytes(welcomeLen); // discard the WELCOME payload

            // Send a malformed frame: length field = 9999 (> MAX_PAYLOAD=4096).
            java.io.OutputStream out = rawBad.getOutputStream();
            byte[] badFrame = new byte[4 + 9999];
            java.nio.ByteBuffer.wrap(badFrame).putInt(9999);
            out.write(badFrame);
            out.flush();

            // The server should close the bad socket shortly.
            Thread.sleep(300);

            // Good client must remain fully functional.
            good.send("PING");
            String pong = good.receive(2_000);
            assertEquals("PONG", pong, "Good client should still work after bad client sent garbage");
        }
    }

    // -------------------------------------------------------------------------
    // IT-9: Multiple PINGs in sequence all get PONG
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-9: Multiple sequential PINGs all receive PONG")
    void multiplePings() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);    // WELCOME
            for (int i = 0; i < 5; i++) {
                client.send("PING");
                assertEquals("PONG", client.receive(2_000), "PONG expected for ping #" + (i + 1));
            }
        }
    }

    // -------------------------------------------------------------------------
    // IT-10: Empty-ish frame (single space) is handled gracefully
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IT-10: Blank verb returns ERR UNKNOWN_COMMAND")
    void blankVerbHandled() throws Exception {
        try (TestClient client = new TestClient("127.0.0.1", server.port())) {
            client.receive(2_000);    // WELCOME
            client.send(" ");         // single space — no recognizable verb
            String response = client.receive(2_000);
            assertEquals("ERR UNKNOWN_COMMAND", response);
        }
    }
}
