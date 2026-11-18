package campusnet.testing;

import campusnet.api.*;
import campusnet.common.PropertiesConfig;
import campusnet.server.chat.core.*;
import campusnet.server.chat.handlers.core.*;
import campusnet.server.chat.handlers.ext.*;
import campusnet.server.chat.net.ChatServer;
import campusnet.server.security.*;

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
