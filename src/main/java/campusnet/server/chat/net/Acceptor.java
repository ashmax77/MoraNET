package campusnet.server.chat.net;

import campusnet.api.ServerContext;
import campusnet.common.Protocol;
import campusnet.server.chat.core.ConnectionRegistryImpl;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Handles OP_ACCEPT events on the ServerSocketChannel (M1).
 *
 * On each OP_ACCEPT:
 *  1. Accepts the raw channel; enforces the max-connections limit.
 *  2. Configures non-blocking mode and registers OP_READ on the selector.
 *  3. Creates an {@link NioConnection}, attaches it to the key.
 *  4. Sends the {@code WELCOME campusnet/1} greeting.
 */
public class Acceptor {

    private static final System.Logger LOG = System.getLogger(Acceptor.class.getName());

    private final ServerContext ctx;
    private final int maxConnections;

    public Acceptor(ServerContext ctx) {
        this.ctx = ctx;
        this.maxConnections = ctx.config().getInt("chat.maxConnections", 100);
    }

    public void accept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return;  // spurious wakeup

        ConnectionRegistryImpl registry = (ConnectionRegistryImpl) ctx.registry();

        // Enforce connection cap — refuse before wasting any resources.
        if (registry.totalConnections() >= maxConnections) {
            LOG.log(System.Logger.Level.WARNING,
                    "Max connections ({0}) reached; rejecting {1}",
                    maxConnections, clientChannel.getRemoteAddress());
            clientChannel.close();
            return;
        }

        // Configure non-blocking and register OP_READ on the selector.
        clientChannel.configureBlocking(false);
        SelectionKey clientKey = clientChannel.register(key.selector(), SelectionKey.OP_READ);

        // Create connection state and attach it to the key.
        NioConnection conn = new NioConnection(clientChannel, clientKey);
        clientKey.attach(conn);

        registry.add(conn);

        // Send protocol greeting.
        conn.send("WELCOME " + Protocol.VERSION);

        LOG.log(System.Logger.Level.INFO,
                "Accepted connection {0} from {1} (total={2})",
                conn.id(), conn.remoteAddress(), registry.totalConnections());
    }
}
