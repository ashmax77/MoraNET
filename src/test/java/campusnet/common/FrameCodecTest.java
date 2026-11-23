package campusnet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    // Helper: build a raw frame ByteBuffer in READ mode.
    private static ByteBuffer frame(String payload) throws FrameCodec.FrameException {
        return FrameCodec.encode(payload);
    }

    // Helper: a server-style buffer (write mode, 8192 bytes capacity).
    private static ByteBuffer serverBuf() {
        return ByteBuffer.allocate(8192); // starts in WRITE mode
    }

    // ── Encode ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("encode: buffer is in read mode, starts at position 0")
    void encodeReturnedBufferIsInReadMode() throws Exception {
        ByteBuffer buf = FrameCodec.encode("PING");
        assertEquals(0, buf.position());
        assertEquals(Protocol.HEADER_SIZE + 4, buf.limit()); // 4 (header) + 4 (PING)
    }

    @Test
    @DisplayName("encode: 4-byte big-endian header contains payload length")
    void encodeHeaderContainsLength() throws Exception {
        ByteBuffer buf = FrameCodec.encode("PING");
        int len = buf.getInt(); // relative read advances position
        assertEquals(4, len);
    }

    @Test
    @DisplayName("encode: empty string throws FrameException")
    void encodeEmptyPayloadThrows() {
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.encode(""));
    }

    @Test
    @DisplayName("encode: payload over MAX_PAYLOAD throws FrameException")
    void encodeOversizedPayloadThrows() {
        String big = "X".repeat(Protocol.MAX_PAYLOAD + 1);
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.encode(big));
    }

    @Test
    @DisplayName("encode: payload of exactly MAX_PAYLOAD bytes succeeds")
    void encodeMaxPayloadSucceeds() throws Exception {
        String maxPayload = "A".repeat(Protocol.MAX_PAYLOAD);
        ByteBuffer buf = FrameCodec.encode(maxPayload);
        assertNotNull(buf);
        assertEquals(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD, buf.limit());
    }

    // ── Decode — normal cases ────────────────────────────────────────────────

    @Test
    @DisplayName("decode: empty buffer returns empty list")
    void decodeEmptyBuffer() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.flip(); // empty buffer in read mode
        assertEquals(List.of(), FrameCodec.decode(buf));
    }

    @Test
    @DisplayName("decode: fewer than 4 bytes returns empty list")
    void decodeLessThanHeader() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.put((byte) 0).put((byte) 0); // only 2 bytes
        buf.flip();
        assertEquals(List.of(), FrameCodec.decode(buf));
        assertEquals(2, buf.remaining()); // bytes untouched
    }

    @Test
    @DisplayName("decode: one complete frame returns one message")
    void decodeOneFrame() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.put(frame("PING")); // simulates channel.read()
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(List.of("PING"), msgs);
    }

    @Test
    @DisplayName("decode: two frames coalesced in one buffer returns two messages")
    void decodeTwoFrames() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.put(frame("MSG hello"));
        buf.put(frame("PING"));
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(2, msgs.size());
        assertEquals("MSG hello", msgs.get(0));
        assertEquals("PING", msgs.get(1));
    }

    @Test
    @DisplayName("decode: header present but payload incomplete returns empty list")
    void decodeHeaderOnlyNoPayload() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.putInt(10); // says 10 bytes coming
        // but no payload bytes
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(List.of(), msgs);
        assertEquals(4, buf.remaining()); // header bytes untouched
    }

    @Test
    @DisplayName("decode: partial payload returns empty list, leaves all bytes intact")
    void decodePartialPayload() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.putInt(5); // 5-byte payload coming
        buf.put("hel".getBytes(StandardCharsets.UTF_8)); // only 3 bytes of payload
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(List.of(), msgs);
        assertEquals(7, buf.remaining()); // 4 header + 3 partial payload
    }

    @Test
    @DisplayName("decode: one complete frame followed by a partial frame returns one message")
    void decodeCompleteAndPartial() throws Exception {
        ByteBuffer buf = serverBuf();
        buf.put(frame("LIST"));    // complete frame
        buf.putInt(99);            // partial second frame: header only, no payload
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(List.of("LIST"), msgs);
        assertEquals(4, buf.remaining()); // 4 bytes of partial header remain
    }

    // ── Decode — byte-by-byte (simulates slow/fragmented delivery) ────────────

    @Test
    @DisplayName("decode: frame arriving one byte at a time eventually decodes")
    void decodeByteByByte() throws Exception {
        byte[] frameBytes = new byte[Protocol.HEADER_SIZE + 4]; // PING = 4 bytes
        ByteBuffer encoded = frame("PING");
        encoded.get(frameBytes);

        ByteBuffer buf = serverBuf();
        List<String> lastResult = List.of();

        for (int i = 0; i < frameBytes.length; i++) {
            buf.put(frameBytes[i]);
            buf.flip();
            lastResult = FrameCodec.decode(buf);
            buf.compact(); // MUST compact after every decode call

            if (i < frameBytes.length - 1) {
                assertEquals(List.of(), lastResult,
                        "Should not decode before all bytes arrive (byte index " + i + ")");
            }
        }
        assertEquals(List.of("PING"), lastResult);
    }

    // ── Decode — error cases ─────────────────────────────────────────────────

    @Test
    @DisplayName("decode: length 0 throws FrameException with FRAME_TOO_LARGE")
    void decodeLengthZeroThrows() {
        ByteBuffer buf = serverBuf();
        buf.putInt(0);
        buf.flip();
        FrameCodec.FrameException ex = assertThrows(FrameCodec.FrameException.class,
                () -> FrameCodec.decode(buf));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    @Test
    @DisplayName("decode: negative length throws FrameException")
    void decodeNegativeLengthThrows() {
        ByteBuffer buf = serverBuf();
        buf.putInt(-1);
        buf.flip();
        assertThrows(FrameCodec.FrameException.class, () -> FrameCodec.decode(buf));
    }

    @Test
    @DisplayName("decode: length above MAX_PAYLOAD throws FrameException")
    void decodeLengthAboveMaxThrows() {
        ByteBuffer buf = serverBuf();
        buf.putInt(Protocol.MAX_PAYLOAD + 1);
        buf.flip();
        FrameCodec.FrameException ex = assertThrows(FrameCodec.FrameException.class,
                () -> FrameCodec.decode(buf));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    // ── Round-trip ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("round-trip: encode then decode returns original string")
    void roundTrip() throws Exception {
        String original = "AUTH alice password123";
        ByteBuffer buf = serverBuf();
        buf.put(FrameCodec.encode(original));
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(List.of(original), msgs);
    }

    @Test
    @DisplayName("round-trip: multi-byte UTF-8 (emoji) survives encode-decode")
    void roundTripEmoji() throws Exception {
        String original = "MSG hello \uD83C\uDF0D"; // 🌍
        ByteBuffer buf = serverBuf();
        buf.put(FrameCodec.encode(original));
        buf.flip();
        List<String> msgs = FrameCodec.decode(buf);
        assertEquals(List.of(original), msgs);
    }
}
