package campusnet.server.chat.net;

import campusnet.api.Connection;
import campusnet.api.Session;

import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

/**
 * Per-channel connection state: SocketChannel, inbound buffer, Outbox, Session (M1).
 * I0 stub: minimal implementation so it compiles. Full implementation in milestone I1.
 */
public class NioConnection implements Connection {

    private static long nextId = 0;

    private final long id;
    private final SocketChannel channel;
    private final Session session;

    public NioConnection(SocketChannel channel) {
        this.id = nextId++;
        this.channel = channel;
        this.session = new Session();
    }

    @Override public long id() { return id; }

    @Override
    public SocketAddress remoteAddress() {
        try { return channel.getRemoteAddress(); }
        catch (Exception e) { return null; }
    }

    @Override public Session session() { return session; }

    @Override public void send(String payload) {
        // TODO(m1, #1): encode and queue
    }

    @Override public void sendAndClose(String payload) {
        send(payload);
        close("sendAndClose");
    }

    @Override public void close(String reason) {
        try { channel.close(); } catch (Exception ignored) {}
    }
}
