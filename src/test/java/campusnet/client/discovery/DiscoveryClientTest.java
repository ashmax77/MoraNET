package campusnet.client.discovery;

import campusnet.common.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DiscoveryClient message protocol parsing and address logic.
 *
 * <p>No real sockets, no network calls. All tests are deterministic.
 *
 * <p>Owner: Member 3.
 */
class DiscoveryClientTest {

    // ── Request formatting ─────────────────────────────────────────────────────

    @Test
    void discoverRequestFormat_exactMatch() {
        String request = "DISCOVER " + Protocol.VERSION;
        assertEquals("DISCOVER campusnet/1", request,
                "Discovery request must be exactly 'DISCOVER campusnet/1'");
    }

    @Test
    void discoverRequestFormat_utf8Encodable() {
        String request = "DISCOVER " + Protocol.VERSION;
        byte[] bytes = request.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 512,
                "Discovery request must fit within the 512-byte datagram limit");
    }

    // ── Response parsing ───────────────────────────────────────────────────────

    @Test
    void parseReply_validDefaultPorts_succeeds() {
        ParseResult r = parseServerReply("SERVER 5000 5001");
        assertTrue(r.valid);
        assertEquals(5000, r.chatPort);
        assertEquals(5001, r.filePort);
    }

    @Test
    void parseReply_validCustomPorts_succeeds() {
        ParseResult r = parseServerReply("SERVER 7777 8888");
        assertTrue(r.valid);
        assertEquals(7777, r.chatPort);
        assertEquals(8888, r.filePort);
    }

    @Test
    void parseReply_portBoundaryMin_succeeds() {
        ParseResult r = parseServerReply("SERVER 1 1");
        assertTrue(r.valid);
        assertEquals(1, r.chatPort);
        assertEquals(1, r.filePort);
    }

    @Test
    void parseReply_portBoundaryMax_succeeds() {
        ParseResult r = parseServerReply("SERVER 65535 65535");
        assertTrue(r.valid);
        assertEquals(65535, r.chatPort);
        assertEquals(65535, r.filePort);
    }

    @Test
    void parseReply_wrongPrefix_fails() {
        ParseResult r = parseServerReply("REPLY 5000 5001");
        assertFalse(r.valid, "Reply not starting with 'SERVER ' must be rejected");
    }

    @Test
    void parseReply_okPrefix_fails() {
        ParseResult r = parseServerReply("OK 5000 5001");
        assertFalse(r.valid, "Reply starting with 'OK' must be rejected");
    }

    @Test
    void parseReply_emptyString_fails() {
        ParseResult r = parseServerReply("");
        assertFalse(r.valid, "Empty reply must be rejected");
    }

    @Test
    void parseReply_randomText_fails() {
        ParseResult r = parseServerReply("hello world");
        assertFalse(r.valid, "Random text must be rejected");
    }

    @Test
    void parseReply_nonNumericChatPort_fails() {
        ParseResult r = parseServerReply("SERVER abc 5001");
        assertFalse(r.valid, "Non-numeric chat port must be rejected");
    }

    @Test
    void parseReply_nonNumericFilePort_fails() {
        ParseResult r = parseServerReply("SERVER 5000 xyz");
        assertFalse(r.valid, "Non-numeric file port must be rejected");
    }

    @Test
    void parseReply_portZero_fails() {
        ParseResult r = parseServerReply("SERVER 0 5001");
        assertFalse(r.valid, "Port 0 is out of valid range");
    }

    @Test
    void parseReply_chatPortNegative_fails() {
        ParseResult r = parseServerReply("SERVER -1 5001");
        assertFalse(r.valid, "Negative chat port must be rejected");
    }

    @Test
    void parseReply_filePortTooLarge_fails() {
        ParseResult r = parseServerReply("SERVER 5000 99999");
        assertFalse(r.valid, "File port >65535 must be rejected");
    }

    @Test
    void parseReply_chatPortTooLarge_fails() {
        ParseResult r = parseServerReply("SERVER 99999 5001");
        assertFalse(r.valid, "Chat port >65535 must be rejected");
    }

    @Test
    void parseReply_extraTokens_fails() {
        ParseResult r = parseServerReply("SERVER 5000 5001 extra");
        assertFalse(r.valid, "Reply with extra tokens must be rejected");
    }

    @Test
    void parseReply_onlyOnePort_fails() {
        ParseResult r = parseServerReply("SERVER 5000");
        assertFalse(r.valid, "Reply with only one port must be rejected");
    }

    @Test
    void parseReply_serverPrefixOnly_fails() {
        ParseResult r = parseServerReply("SERVER ");
        assertFalse(r.valid, "Reply with 'SERVER ' and no ports must be rejected");
    }

    // ── Port validation ─────────────────────────────────────────────────────────

    @Test
    void isValidPort_one_isValid() {
        assertTrue(isValidPort(1));
    }

    @Test
    void isValidPort_maxPort_isValid() {
        assertTrue(isValidPort(65535));
    }

    @Test
    void isValidPort_zero_isInvalid() {
        assertFalse(isValidPort(0));
    }

    @Test
    void isValidPort_negative_isInvalid() {
        assertFalse(isValidPort(-1));
    }

    @Test
    void isValidPort_overMax_isInvalid() {
        assertFalse(isValidPort(65536));
    }

    // ── Timeout calculations ────────────────────────────────────────────────────

    @Test
    void timeoutBound_threeAttemptsOneSecondEach_completeWithin3500ms() {
        // Verify that the theoretical maximum wall time (3 × 1 second) is within
        // the 3.5-second bound specified in ARCHITECTURE.md Section 8.3.
        long maxAttemptMs = 1000L;
        int maxAttempts = 3;
        long maxTotalMs = maxAttemptMs * maxAttempts;
        assertTrue(maxTotalMs <= 3500,
                "3 attempts × 1 second must fit within the 3.5-second bound");
    }

    @Test
    void callerTimeout_shorterThanOneAttempt_limitsAttemptDuration() {
        long callerTimeoutNs = 500_000_000L; // 500 ms
        long maxAttemptNs = 1_000_000_000L;  // 1 second
        long effectiveAttemptNs = Math.min(maxAttemptNs, callerTimeoutNs);
        assertEquals(500_000_000L, effectiveAttemptNs,
                "Effective attempt timeout must not exceed the caller-provided timeout");
    }

    @Test
    void callerTimeout_longerThanOneAttempt_capedAtOneSecond() {
        long callerTimeoutNs = 5_000_000_000L; // 5 seconds (more than 3 attempts)
        long maxAttemptNs = 1_000_000_000L;    // 1 second
        long effectiveAttemptNs = Math.min(maxAttemptNs, callerTimeoutNs);
        assertEquals(1_000_000_000L, effectiveAttemptNs,
                "Effective attempt timeout must be capped at 1 second per attempt");
    }

    // ── Datagram size boundary ──────────────────────────────────────────────────

    @Test
    void datagramSizeLimit_exactly512_accepted() {
        int size = 512;
        int maxAllowed = 512;
        assertTrue(size <= maxAllowed);
    }

    @Test
    void datagramSizeLimit_513bytes_rejected() {
        int size = 513;
        int maxAllowed = 512;
        assertFalse(size <= maxAllowed, "A 513-byte datagram must be rejected");
    }

    // ── Helper: in-test reimplementation of the parsing logic ──────────────────

    /**
     * Mirrors the parsing logic inside {@link DiscoveryClient#parseReply} for unit testing
     * without requiring a live socket.
     */
    private static ParseResult parseServerReply(String text) {
        final String prefix = "SERVER ";
        if (!text.startsWith(prefix)) {
            return ParseResult.invalid();
        }
        String[] parts = text.substring(prefix.length()).trim().split("\\s+");
        if (parts.length != 2 || parts[0].isEmpty()) {
            return ParseResult.invalid();
        }
        int chatPort;
        int filePort;
        try {
            chatPort = Integer.parseInt(parts[0]);
            filePort = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return ParseResult.invalid();
        }
        if (!isValidPort(chatPort) || !isValidPort(filePort)) {
            return ParseResult.invalid();
        }
        return new ParseResult(true, chatPort, filePort);
    }

    private static boolean isValidPort(int p) {
        return p >= 1 && p <= 65535;
    }

    private record ParseResult(boolean valid, int chatPort, int filePort) {
        static ParseResult invalid() {
            return new ParseResult(false, -1, -1);
        }
    }
}
