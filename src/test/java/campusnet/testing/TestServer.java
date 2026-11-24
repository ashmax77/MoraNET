package campusnet.testing;

import campusnet.api.AuthService;
import campusnet.api.Config;
import campusnet.api.ConnectionRegistry;
import campusnet.api.ServerContext;
import campusnet.common.PropertiesConfig;
import campusnet.server.chat.core.ConnectionRegistryImpl;
import campusnet.server.chat.core.DefaultServerContext;
import campusnet.server.chat.core.Dispatcher;
import campusnet.server.chat.handlers.core.ListHandler;
import campusnet.server.chat.handlers.core.MsgHandler;
import campusnet.server.chat.handlers.core.PingHandler;
import campusnet.server.chat.handlers.core.QuitHandler;
import campusnet.server.chat.handlers.ext.AuthHandler;
import campusnet.server.chat.handlers.ext.FileGetHandler;
import campusnet.server.chat.handlers.ext.FilePutHandler;
import campusnet.server.chat.handlers.ext.PrivateHandler;
import campusnet.server.chat.net.ChatServer;
import campusnet.server.security.InMemoryAuthService;
import campusnet.server.security.InMemoryTokenService;

import java.io.IOException;
import java.util.Properties;

/**
 * Convenience wrapper that starts a ChatServer on an ephemeral port for integration tests.
 *
 * Usage:
 *   TestServer server = new TestServer();
 *   server.start();
 *   int port = server.port();
 *   // ... run tests ...
 *   server.stop();
 */
public class TestServer {

    private final ChatServer chatServer;

    public TestServer() throws IOException {
        this(new Properties());
    }

    public TestServer(Properties overrides) throws IOException {
        Properties props = new Properties();
        props.setProperty("chat.port", "0"); // 0 = ephemeral port chosen by OS
        props.setProperty("chat.authTimeoutSec",  "30");
        props.setProperty("chat.idleTimeoutSec",  "120");
        props.setProperty("chat.maxOutboundBytes", "262144");
        props.putAll(overrides);

        Config config = new PropertiesConfig(props);
        InMemoryAuthService auth = new InMemoryAuthService(config);
        InMemoryTokenService tokens = new InMemoryTokenService(config);
        ConnectionRegistryImpl registry = new ConnectionRegistryImpl();

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register(new MsgHandler());
        dispatcher.register(new ListHandler());
        dispatcher.register(new PingHandler());
        dispatcher.register(new QuitHandler());
        dispatcher.register(new AuthHandler());
        dispatcher.register(new PrivateHandler());
        dispatcher.register(new FilePutHandler());
        dispatcher.register(new FileGetHandler());

        ServerContext ctx = new DefaultServerContext(config, registry, auth, tokens);
        dispatcher.setContext(ctx);

        this.chatServer = new ChatServer(ctx, dispatcher);
    }

    public void start() throws IOException {
        chatServer.start();
    }

    public void stop() {
        chatServer.stop();
    }

    /** The actual port the server is listening on. Call after start(). */
    public int port() {
        return chatServer.localPort();
    }
}
