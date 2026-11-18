package campusnet.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes length-prefixed chat frames.
 *
 * Wire format:
 *   [4-byte big-endian int N][N bytes of UTF-8 payload]
 *   where Protocol.MIN_PAYLOAD (1) <= N <= Protocol.MAX_PAYLOAD (4096)
 *
 * DECODE CONTRACT:
 *   - buf must be in READ mode (after flip()) when decode() is called.
 *   - decode() may consume 0, 1, or many complete frames from buf.
 *   - After decode() returns, the caller MUST call buf.compact() to preserve
 *     any partial frame data for the next read.
 *   - decode() does NOT call flip() or compact() itself.
 *
 * ENCODE CONTRACT:
 *   - encode() returns a NEW ByteBuffer in READ mode, ready for channel.write().
 *   - The returned buffer is independent — multiple threads may each hold their own.
 */
public final class FrameCodec {

    private FrameCodec() {}

    /** Thrown when a frame violates the protocol length constraints. */
    public static final class FrameException extends Exception {
        private final ErrorCode code;

        public FrameException(ErrorCode code) {
            super(code.name());
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }

    /**
     * Decode all complete frames from buf.
     *
     * PRECONDITION:  buf is in READ mode (position points to first unread byte,
     *                limit points past the last available byte).
     * POSTCONDITION: buf.position() has advanced past every fully consumed frame.
     *                Any partial frame remains between position and limit.
     *                THE CALLER MUST CALL buf.compact() after this method returns.
     *
     * @param buf the inbound channel buffer in READ mode
     * @return list of decoded payloads (may be empty if no complete frame is available)
     * @throws FrameException if a frame header declares a length outside [1, MAX_PAYLOAD]
     */
    public static List<String> decode(ByteBuffer buf) throws FrameException {
        List<String> out = new ArrayList<>();

        while (buf.remaining() >= Protocol.HEADER_SIZE) {
            // PEEK at the length without advancing position (getInt at index, not relative get).
            int len = buf.getInt(buf.position());

            // Validate before consuming anything.
            if (len < Protocol.MIN_PAYLOAD || len > Protocol.MAX_PAYLOAD) {
                throw new FrameException(ErrorCode.FRAME_TOO_LARGE);
            }

            // Check whether the full payload has arrived.
            if (buf.remaining() < Protocol.HEADER_SIZE + len) {
                break; // partial frame — wait for more bytes from the network
            }

            // Now consume: skip the 4-byte header, then read the payload.
            buf.position(buf.position() + Protocol.HEADER_SIZE);
            byte[] payload = new byte[len];
            buf.get(payload);

            out.add(new String(payload, StandardCharsets.UTF_8));
        }

        return out;
    }

    /**
     * Encode a string payload as a length-prefixed frame.
     *
     * @param payload the message string
     * @return a new ByteBuffer in READ mode (position=0, limit=HEADER_SIZE+payload.length)
     * @throws FrameException if the UTF-8 encoding of payload is outside [1, MAX_PAYLOAD] bytes
     */
    public static ByteBuffer encode(String payload) throws FrameException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        if (bytes.length < Protocol.MIN_PAYLOAD || bytes.length > Protocol.MAX_PAYLOAD) {
            throw new FrameException(ErrorCode.FRAME_TOO_LARGE);
        }

        // Allocate header + payload in one buffer and fill it.
        ByteBuffer frame = ByteBuffer.allocate(Protocol.HEADER_SIZE + bytes.length);
        frame.putInt(bytes.length); // 4-byte big-endian length
        frame.put(bytes);           // payload bytes
        frame.flip();               // switch to read mode: position=0, limit=total size
        return frame;
    }
}
