package campusnet.server.chat.net;

import campusnet.api.ServerContext;
import campusnet.api.Service;
import campusnet.server.chat.core.Dispatcher;
import campusnet.common.Protocol;

import java.io.IOException;

/**
 * Non-blocking chat server (M1).
 * I0 stub: starts and stops cleanly; does not yet accept connections.
 * Constructor signature is FROZEN — do not change.
 */
public class ChatServer implements Service {

    private final ServerContext ctx;
    private final Dispatcher dispatcher;
    private volatile int port;

    public ChatServer(ServerContext ctx, Dispatcher dispatcher) {
        this.ctx = ctx;
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() { return "chat"; }

    @Override
    public void start() throws IOException {
        // TODO(m1, #1): implement the selector event loop
        port = ctx.config().getInt("chat.port", Protocol.DEFAULT_CHAT_PORT);
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                "ChatServer stub started (not yet accepting connections)");
    }

    @Override
    public void stop() {
        // TODO(m1, #1): close selector and server channel
    }

    @Override
    public int localPort() { return port; }
}
