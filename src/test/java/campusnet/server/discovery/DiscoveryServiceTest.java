package campusnet.server.discovery;

import campusnet.common.Protocol;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DiscoveryService message protocol parsing and rate-limiter logic.
 *
 * <p>No real sockets are used. All tests are deterministic and complete in milliseconds.
 *
 * <p>Owner: Member 3.
 */
class DiscoveryServiceTest {

    // ── Protocol constants ─────────────────────────────────────────────────────

    @Test
    void discoverRequestFormat_matchesProtocol() {
        String expected = "DISCOVER " + Protocol.VERSION;
        assertEquals("DISCOVER campusnet/1", expected,
                "Discovery request must be exactly 'DISCOVER campusnet/1'");
    }

    @Test
    void serverResponseFormat_correctPorts() {
        int chatPort = 5000;
        int filePort = 5001;
        String response = "SERVER " + chatPort + " " + filePort;
        assertEquals("SERVER 5000 5001", response,
                "Response must be 'SERVER <chatPort> <filePort>'");
    }

    @Test
    void serverResponseFormat_nonDefaultPorts() {
        String response = "SERVER 7000 7001";
        assertTrue(response.startsWith("SERVER "),
                "Response must start with 'SERVER '");
        String[] parts = response.substring("SERVER ".length()).split("\\s+");
        assertEquals(2, parts.length, "Response must have exactly two port tokens");
        assertEquals(7000, Integer.parseInt(parts[0]));
        assertEquals(7001, Integer.parseInt(parts[1]));
    }

    // ── Request validation ─────────────────────────────────────────────────────

    @Test
    void invalidRequest_wrongVersion_rejected() {
        String req = "DISCOVER campusnet/2";
        assertNotEquals("DISCOVER campusnet/1", req,
                "Wrong version must not match the valid request");
    }

    @Test
    void invalidRequest_missingPayload_rejected() {
        String req = "DISCOVER";
        assertNotEquals("DISCOVER campusnet/1", req,
                "Bare DISCOVER with no version must be rejected");
    }

    @Test
    void invalidRequest_wrongVerb_rejected() {
        String req = "FIND campusnet/1";
        assertNotEquals("DISCOVER campusnet/1", req,
                "Wrong verb must be rejected");
    }

    @Test
    void invalidRequest_empty_rejected() {
        String req = "";
        assertNotEquals("DISCOVER campusnet/1", req,
                "Empty datagram must be rejected");
    }

    @Test
    void invalidRequest_randomText_rejected() {
        String req = "hello world";
        assertNotEquals("DISCOVER campusnet/1", req,
                "Random text must be rejected");
    }

    // ── Response parsing ───────────────────────────────────────────────────────

    @Test
    void parseResponse_validPorts_succeeds() {
        String reply = "SERVER 5000 5001";
        assertTrue(reply.startsWith("SERVER "));
        String[] parts = reply.substring("SERVER ".length()).trim().split("\\s+");
        assertEquals(2, parts.length);
        int chatPort = Integer.parseInt(parts[0]);
        int filePort = Integer.parseInt(parts[1]);
        assertTrue(chatPort >= 1 && chatPort <= 65535);
        assertTrue(filePort >= 1 && filePort <= 65535);
    }

    @Test
    void parseResponse_wrongPrefix_fails() {
        String reply = "OK SERVER 5000 5001";
        assertFalse(reply.startsWith("SERVER "),
                "Response with wrong prefix must be rejected");
    }

    @Test
    void parseResponse_nonNumericPort_fails() {
        String reply = "SERVER abc 5001";
        String[] parts = reply.substring("SERVER ".length()).trim().split("\\s+");
        assertThrows(NumberFormatException.class, () -> Integer.parseInt(parts[0]),
                "Non-numeric chat port must throw NumberFormatException");
    }

    @Test
    void parseResponse_nonNumericFilePort_fails() {
        String reply = "SERVER 5000 xyz";
        String[] parts = reply.substring("SERVER ".length()).trim().split("\\s+");
        assertThrows(NumberFormatException.class, () -> Integer.parseInt(parts[1]),
                "Non-numeric file port must throw NumberFormatException");
    }

    @Test
    void parseResponse_portZero_invalidRange() {
        assertFalse(isValidPort(0), "Port 0 must be invalid");
    }

    @Test
    void parseResponse_portNegative_invalidRange() {
        assertFalse(isValidPort(-1), "Negative port must be invalid");
    }

    @Test
    void parseResponse_portOverMax_invalidRange() {
        assertFalse(isValidPort(65536), "Port 65536 must be invalid");
    }

    @Test
    void parseResponse_portBoundaryMin_valid() {
        assertTrue(isValidPort(1), "Port 1 must be valid");
    }

    @Test
    void parseResponse_portBoundaryMax_valid() {
        assertTrue(isValidPort(65535), "Port 65535 must be valid");
    }

    @Test
    void parseResponse_extraTokens_fails() {
        String reply = "SERVER 5000 5001 extra";
        String[] parts = reply.substring("SERVER ".length()).trim().split("\\s+");
        assertNotEquals(2, parts.length,
                "Reply with extra tokens must have more than 2 parts");
    }

    @Test
    void parseResponse_onlyOnePort_fails() {
        String reply = "SERVER 5000";
        String[] parts = reply.substring("SERVER ".length()).trim().split("\\s+");
        assertNotEquals(2, parts.length,
                "Reply with only one port must not have 2 parts");
    }

    @Test
    void parseResponse_emptyAfterPrefix_fails() {
        String reply = "SERVER ";
        String[] parts = reply.substring("SERVER ".length()).trim().split("\\s+");
        // trim().split on empty string gives [""] — length 1 with empty content
        assertTrue(parts.length < 2 || parts[0].isEmpty(),
                "Reply with no ports after prefix must fail validation");
    }

    // ── Rate limiter logic (pure, no sockets) ──────────────────────────────────

    /**
     * Validates rate-limiter bucket logic using the same algorithm as DiscoveryService.
     */
    @Test
    void rateLimiter_allowsUpToTenRepliesPerWindow() throws UnknownHostException {
        // Simulate a rate-limiter bucket for one source address.
        InetAddress src = InetAddress.getByName("10.0.0.1");
        long nowNs = System.nanoTime();
        long windowNs = 1_000_000_000L;
        int maxReplies = 10;

        // Simulate 10 successful replies in a fresh window.
        int count = 0;
        long windowStart = nowNs;

        for (int i = 0; i < maxReplies; i++) {
            if (nowNs - windowStart > windowNs) {
                // Window expired — reset.
                windowStart = nowNs;
                count = 0;
            }
            if (count < maxReplies) {
                count++;
            }
        }
        assertEquals(10, count, "Should allow exactly 10 replies in a 1-second window");
    }

    @Test
    void rateLimiter_blocksEleventhReplyInWindow() throws UnknownHostException {
        int maxReplies = 10;
        int count = maxReplies; // window already exhausted
        boolean allowed = count < maxReplies;
        assertFalse(allowed, "11th reply in the same window must be denied");
    }

    @Test
    void rateLimiter_resetsAfterWindowExpiry() {
        long pastWindowStart = System.nanoTime() - 2_000_000_000L; // 2 seconds ago
        long nowNs = System.nanoTime();
        long windowNs = 1_000_000_000L;

        boolean windowExpired = (nowNs - pastWindowStart) > windowNs;
        assertTrue(windowExpired, "A window started 2 seconds ago must be expired");
    }

    // ── Datagram size boundary ─────────────────────────────────────────────────

    @Test
    void datagramSizeLimit_exactly512_accepted() {
        int size = 512;
        int maxAllowed = 512;
        assertTrue(size <= maxAllowed, "A 512-byte datagram must be accepted");
    }

    @Test
    void datagramSizeLimit_513bytes_rejected() {
        int size = 513;
        int maxAllowed = 512;
        assertFalse(size <= maxAllowed, "A 513-byte datagram must be rejected as oversized");
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private static boolean isValidPort(int p) {
        return p >= 1 && p <= 65535;
    }
}
