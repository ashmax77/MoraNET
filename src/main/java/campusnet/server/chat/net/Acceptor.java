package campusnet.server.chat.net;

import campusnet.api.ServerContext;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * Handles OP_ACCEPT events on the ServerSocketChannel (M1).
 * I0 stub: empty placeholder. Full implementation in milestone I1.
 */
public class Acceptor {

    private final ServerContext ctx;

    public Acceptor(ServerContext ctx) {
        this.ctx = ctx;
    }

    public void accept(SelectionKey key) throws IOException {
        // TODO(m1, #1): implement accept, create NioConnection, send WELCOME
    }
}
