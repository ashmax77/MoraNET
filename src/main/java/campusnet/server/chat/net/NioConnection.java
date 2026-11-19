package campusnet.server.chat.net;

import campusnet.api.Connection;
import campusnet.api.Session;
import campusnet.common.FrameCodec;
import campusnet.common.Protocol;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

/**
 * Per-channel connection state: SocketChannel, inbound buffer, Outbox, Session (M1).
 *
 * All methods must be called from the selector thread only, except {@code close()},
 * which is idempotent and safe to call multiple times.
 */
public class NioConnection implements Connection {

    private static final System.Logger LOG = System.getLogger(NioConnection.class.getName());

    /** Shared counter — only the selector thread increments this. */
    private static long nextId = 0;

    private final long id;
    private final SocketChannel channel;
    private final SelectionKey key;
    private final Session session;
    private final Outbox outbox;
    private final ByteBuffer inBuf = ByteBuffer.allocate(8192);

    private final long connectedAtNs = System.nanoTime();
    private long lastActivityNs = connectedAtNs;
    private volatile boolean closing = false;
    private volatile boolean closed  = false;

    public NioConnection(SocketChannel channel, SelectionKey key) {
        this.id      = nextId++;
        this.channel = channel;
        this.key     = key;
        this.session = new Session();
        this.outbox  = new Outbox(key);
    }

    // -------------------------------------------------------------------------
    // Connection interface
    // -------------------------------------------------------------------------

    @Override public long id() { return id; }

    @Override
    public SocketAddress remoteAddress() {
        try { return channel.getRemoteAddress(); }
        catch (Exception e) { return null; }
    }

    @Override public Session session() { return session; }

    @Override
    public void send(String payload) {
        if (closed) return;
        try {
            ByteBuffer frame = FrameCodec.encode(payload);
            outbox.enqueue(frame, channel);
        } catch (FrameCodec.FrameException e) {
            LOG.log(System.Logger.Level.WARNING, "Frame encode error for conn {0}: {1}", id, e.getMessage());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Write error for conn {0}: {1}", id, e.getMessage());
            close("write-error");
        }
    }

    @Override
    public void sendAndClose(String payload) {
        send(payload);
        closing = true;
        // Actual close happens in the event loop once the outbox is empty.
        if (outbox.isEmpty()) {
            close("sendAndClose");
        }
    }

    @Override
    public void close(String reason) {
        if (closed) return;
        closed = true;
        key.cancel();
        try { channel.close(); } catch (Exception ignored) {}
        LOG.log(System.Logger.Level.DEBUG, "Connection {0} closed: {1}", id, reason);
    }

    // -------------------------------------------------------------------------
    // Selector-thread I/O helpers
    // -------------------------------------------------------------------------

    /**
     * Reads bytes from the channel into the inbound buffer and returns any
     * complete decoded frames.  Returns an empty list if the channel is not
     * yet readable or no full frame has arrived.
     * Returns {@code null} to signal that the remote peer closed the connection.
     */
    public List<String> read() throws IOException, FrameCodec.FrameException {
        int n = channel.read(inBuf);
        if (n == -1) return null;              // EOF — peer closed
        lastActivityNs = System.nanoTime();
        inBuf.flip();
        List<String> frames = FrameCodec.decode(inBuf);
        inBuf.compact();
        return frames;
    }

    /**
     * Flushes the outbound queue.  Returns {@code true} when the queue is empty.
     * If {@link #closing} is {@code true} and the queue empties, closes the connection.
     */
    public boolean flush() throws IOException {
        boolean done = outbox.flush(channel);
        if (done && closing) {
            close("outbox-drained");
        }
        return done;
    }

    // -------------------------------------------------------------------------
    // Timeout / backpressure helpers (selector thread only)
    // -------------------------------------------------------------------------

    public long connectedAtNs()  { return connectedAtNs; }
    public long lastActivityNs() { return lastActivityNs; }
    public long pendingBytes()  { return outbox.pendingBytes(); }
    public boolean isClosing()  { return closing; }
    public boolean isClosed()   { return closed; }
}
