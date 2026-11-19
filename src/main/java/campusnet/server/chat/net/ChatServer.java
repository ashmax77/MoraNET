package campusnet.server.chat.net;

import campusnet.api.ServerContext;
import campusnet.api.Service;
import campusnet.common.Protocol;
import campusnet.server.chat.core.Dispatcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

/**
 * Non-blocking chat server (M1).
 *
 * Binds the chat TCP port, starts the NIO {@link EventLoop} on a daemon thread,
 * and cleans up on {@link #stop()}.
 *
 * Constructor signature is FROZEN — do not change.
 */
public class ChatServer implements Service {

    private static final System.Logger LOG = System.getLogger(ChatServer.class.getName());

    private final ServerContext ctx;
    private final Dispatcher    dispatcher;

    private Selector          selector;
    private ServerSocketChannel serverChannel;
    private EventLoop         eventLoop;
    private Thread            loopThread;
    private volatile int      port;

    public ChatServer(ServerContext ctx, Dispatcher dispatcher) {
        this.ctx        = ctx;
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() { return "chat"; }

    @Override
    public void start() throws IOException {
        int configPort = ctx.config().getInt("chat.port", Protocol.DEFAULT_CHAT_PORT);

        // Open selector and server channel.
        selector      = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(configPort));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        // Record the actual bound port (useful in tests with port 0).
        port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        // Wire up accept handler and event loop.
        Acceptor acceptor = new Acceptor(ctx);
        eventLoop = new EventLoop(ctx, dispatcher, acceptor, selector);

        loopThread = new Thread(eventLoop, "chat-event-loop");
        loopThread.setDaemon(true);
        loopThread.start();

        LOG.log(System.Logger.Level.INFO, "ChatServer listening on port {0}", port);
    }

    @Override
    public void stop() {
        if (eventLoop != null) eventLoop.stop();
        try {
            if (selector      != null) selector.close();
            if (serverChannel != null) serverChannel.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Error closing ChatServer: {0}", e.getMessage());
        }
        if (loopThread != null) {
            try { loopThread.join(3_000L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        LOG.log(System.Logger.Level.INFO, "ChatServer stopped.");
    }

    @Override
    public int localPort() { return port; }
}
