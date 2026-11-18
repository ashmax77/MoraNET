package campusnet.server;

import campusnet.api.Config;
import campusnet.api.ServerContext;
import campusnet.api.Service;
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
import campusnet.server.discovery.DiscoveryService;
import campusnet.server.file.FileServer;
import campusnet.server.security.InMemoryAuthService;
import campusnet.server.security.InMemoryTokenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CampusNet server entry point.
 *
 * Usage:
 *   java -cp target/classes campusnet.server.ServerMain
 *   java -cp target/classes campusnet.server.ServerMain config/server.properties
 *
 * Starts three services:
 *   1. ChatServer      — TCP, chat and command dispatch
 *   2. FileServer      — TCP, file upload and download
 *   3. DiscoveryService — UDP, LAN server discovery
 *
 * DO NOT EDIT after the skeleton PR merges (I0). This file is owned by M1 and frozen.
 */
public class ServerMain {

    private static final System.Logger LOG = System.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        // --- Load configuration ---
        Config config = loadConfig(args);

        // --- Build shared services (M5 stubs for now) ---
        InMemoryAuthService auth = new InMemoryAuthService(config);
        InMemoryTokenService tokens = new InMemoryTokenService(config);
        ConnectionRegistryImpl registry = new ConnectionRegistryImpl();

        // --- Build Dispatcher and register all handlers ---
        // ServerContext is not yet needed for Dispatcher construction; pass later.
        Dispatcher dispatcher = new Dispatcher();
        // Core handlers (M1)
        dispatcher.register(new MsgHandler());
        dispatcher.register(new ListHandler());
        dispatcher.register(new PingHandler());
        dispatcher.register(new QuitHandler());
        // Ext handlers (M5 stubs for now)
        dispatcher.register(new AuthHandler());
        dispatcher.register(new PrivateHandler());
        dispatcher.register(new FilePutHandler());
        dispatcher.register(new FileGetHandler());

        // --- Build ServerContext ---
        ServerContext ctx = new DefaultServerContext(config, registry, auth, tokens);

        // --- Wire Dispatcher to context ---
        dispatcher.setContext(ctx);

        // --- Create the three services ---
        ChatServer chatServer = new ChatServer(ctx, dispatcher);
        FileServer fileServer = new FileServer(ctx);
        DiscoveryService discoveryService = new DiscoveryService(ctx);

        List<Service> services = List.of(chatServer, fileServer, discoveryService);

        // --- Start all services ---
        for (Service s : services) {
            s.start();
            LOG.log(System.Logger.Level.INFO, "{0} started on port {1}", s.name(), s.localPort());
        }

        printBanner(config, chatServer, fileServer, discoveryService);

        // --- Shutdown hook for Ctrl+C ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.log(System.Logger.Level.INFO, "Shutting down...");
            for (Service s : services) {
                s.stop();
            }
        }, "shutdown-hook"));

        // --- Main thread parks here ---
        Thread.currentThread().join();
    }

    private static Config loadConfig(String[] args) throws IOException {
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            if (Files.exists(path)) {
                LOG.log(System.Logger.Level.INFO, "Loading config from {0}", path);
                return PropertiesConfig.load(path);
            }
        }
        LOG.log(System.Logger.Level.INFO, "Using default configuration");
        return PropertiesConfig.empty();
    }

    private static void printBanner(Config cfg, ChatServer chat, FileServer file, DiscoveryService disc) {
        System.out.println("=========================================");
        System.out.println("  CampusNet NIO Server");
        System.out.println("  Chat       port : " + chat.localPort());
        System.out.println("  File       port : " + file.localPort());
        System.out.println("  Discovery  port : " + disc.localPort());
        System.out.println("=========================================");
        System.out.println("Press Ctrl+C to stop.");
    }
}
