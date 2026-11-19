package campusnet.testing;

import campusnet.api.Connection;
import campusnet.api.Session;

import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fake Connection for unit testing handlers.
 * Records every message sent via send() and sendAndClose().
 * Thread-safe (synchronized list) so it can be used from any test thread.
 */
public class FakeConnection implements Connection {

    private static long nextId = 1000;

    private final long id;
    private final Session session = new Session();
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private boolean closed = false;
    private String closeReason = null;

    public FakeConnection() {
        this.id = nextId++;
    }

    @Override public long id() { return id; }

    @Override
    public SocketAddress remoteAddress() {
        return new InetSocketAddress("127.0.0.1", 12345);
    }

    @Override public Session session() { return session; }

    @Override
    public void send(String payload) {
        sent.add(payload);
    }

    @Override
    public void sendAndClose(String payload) {
        sent.add(payload);
        close("sendAndClose");
    }

    @Override
    public void close(String reason) {
        this.closed = true;
        this.closeReason = reason;
    }

    // ── Assertions helpers ───────────────────────────────────────────────────

    /** All messages sent to this connection, in order. */
    public List<String> sent() {
        return Collections.unmodifiableList(sent);
    }

    /** The last message sent, or null if nothing was sent. */
    public String lastSent() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    /** True if close() or sendAndClose() was called. */
    public boolean isClosed() { return closed; }

    /** The reason passed to close(), or null. */
    public String closeReason() { return closeReason; }

    /** True if any sent message contains the given substring. */
    public boolean sentContaining(String substring) {
        return sent.stream().anyMatch(m -> m.contains(substring));
    }

    /** Clear all recorded sent messages. */
    public void clearSent() { sent.clear(); }
}
