package campusnet.e2e;

import campusnet.api.Config;
import campusnet.api.ServerInfo;
import campusnet.client.discovery.DiscoveryClient;
import campusnet.client.file.FileClient;
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
import campusnet.testing.TestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class CampusNetE2EIT {

    private ChatServer chatServer;
    private FileServer fileServer;
    private DiscoveryService discoveryService;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServices() {
        if (discoveryService != null) {
            discoveryService.stop();
        }
        if (fileServer != null) {
            fileServer.stop();
        }
        if (chatServer != null) {
            chatServer.stop();
        }
    }

    private int findFreeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private String receiveUntil(TestClient client, String prefix) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            String frame = client.receive(2_000);
            if (frame.startsWith(prefix)) {
                return frame;
            }
        }
        throw new java.io.IOException("Did not receive frame starting with " + prefix);
    }

    @Test
    void completeDemoFlow() throws Exception {
        int chatPort = findFreeTcpPort();
        int filePort = findFreeTcpPort();
        Properties properties = new Properties();
        properties.setProperty("chat.port", Integer.toString(chatPort));
        properties.setProperty("chat.maxConnections", "250");
        properties.setProperty("chat.authTimeoutSec", "30");
        properties.setProperty("chat.idleTimeoutSec", "120");
        properties.setProperty("chat.maxOutboundBytes", "262144");
        properties.setProperty("file.port", Integer.toString(filePort));
        properties.setProperty("file.storageDir", tempDir.resolve("storage").toString());
        properties.setProperty("file.maxBytes", "52428800");
        properties.setProperty("file.idleTimeoutSec", "5");
        properties.setProperty("discovery.port", "0");
        properties.setProperty("token.ttlSec", "60");
        properties.setProperty("token.maxPerUser", "100");

        Config config = new PropertiesConfig(properties);
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

        DefaultServerContext context =
                new DefaultServerContext(config, registry, auth, tokens);
        dispatcher.setContext(context);

        chatServer = new ChatServer(context, dispatcher);
        fileServer = new FileServer(context);
        discoveryService = new DiscoveryService(context);
        chatServer.start();
        fileServer.start();
        discoveryService.start();

        DiscoveryClient discovery = new DiscoveryClient(discoveryService.localPort());
        Optional<ServerInfo> discovered =
                discovery.locate(Duration.ofSeconds(3));
        assertTrue(discovered.isPresent());
        ServerInfo server = discovered.get();
        assertEquals(chatServer.localPort(), server.chatPort());
        assertEquals(fileServer.localPort(), server.filePort());

        try (TestClient alice = new TestClient("127.0.0.1", chatServer.localPort());
             TestClient bob = new TestClient("127.0.0.1", chatServer.localPort());
             TestClient partner = new TestClient("127.0.0.1", chatServer.localPort())) {
            alice.receive(2_000);
            bob.receive(2_000);
            partner.receive(2_000);

            alice.send("AUTH alice pw1");
            assertEquals("OK AUTH alice STUDENT", alice.receive(2_000));
            bob.send("AUTH bob pw2");
            assertEquals("OK AUTH bob LECTURER", bob.receive(2_000));
            partner.send("AUTH partner pw3");
            assertEquals("OK AUTH partner EXTERNAL", partner.receive(2_000));

            alice.send("PING");
            assertEquals("PONG", receiveUntil(alice, "PONG"));
            bob.send("LIST");
            assertTrue(receiveUntil(bob, "USERS ").startsWith("USERS "));

            bob.send("PRIVATE alice private hello");
            assertEquals("PRIVATE from bob: private hello",
                    receiveUntil(alice, "PRIVATE from bob"));
            assertEquals("OK PRIVATE_SENT",
                    receiveUntil(bob, "OK PRIVATE_SENT"));

            partner.send("PRIVATE alice blocked");
            assertEquals("ERR ACCESS_DENIED", partner.receive(2_000));
            partner.send("FILE_PUT 1 blocked.txt");
            assertEquals("ERR ACCESS_DENIED", partner.receive(2_000));

            byte[] original = new byte[5 * 1024 * 1024];
            for (int i = 0; i < original.length; i++) {
                original[i] = (byte) (i * 31);
            }
            Path local = tempDir.resolve("demo.bin");
            Files.write(local, original);

            alice.send("FILE_PUT " + original.length + " demo.bin");
            String putResponse = alice.receive(5_000);
            assertTrue(putResponse.startsWith("OK FILE_READY "));
            String putToken = putResponse.split("\\s+")[2];
            new FileClient().upload(
                    new ServerInfo("127.0.0.1",
                            chatServer.localPort(), fileServer.localPort()),
                    putToken,
                    local);

            bob.send("FILE_GET demo.bin");
            String getResponse = bob.receive(5_000);
            assertTrue(getResponse.startsWith("OK FILE_READY "));
            String getToken = getResponse.split("\\s+")[2];
            Path downloaded = tempDir.resolve("demo-downloaded.bin");
            new FileClient().download(
                    new ServerInfo("127.0.0.1",
                            chatServer.localPort(), fileServer.localPort()),
                    getToken,
                    downloaded);

            byte[] downloadedBytes = Files.readAllBytes(downloaded);
            assertArrayEquals(
                    MessageDigest.getInstance("SHA-256").digest(original),
                    MessageDigest.getInstance("SHA-256").digest(downloadedBytes));

            try (SocketChannel replay = SocketChannel.open(
                    new java.net.InetSocketAddress("127.0.0.1",
                            fileServer.localPort()))) {
                writeHeader(replay, "GET " + getToken);
                assertEquals("ERR BAD_TOKEN", readLine(replay));
            }
        }
    }

    private void writeHeader(SocketChannel channel, String header) throws Exception {
        ByteBuffer bytes =
                ByteBuffer.wrap((header + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private String readLine(SocketChannel channel) throws Exception {
        ByteBuffer one = ByteBuffer.allocate(1);
        StringBuilder result = new StringBuilder();
        while (true) {
            one.clear();
            int read = channel.read(one);
            if (read == -1) {
                throw new java.io.IOException("Server closed connection");
            }
            if (read == 0) {
                continue;
            }
            one.flip();
            char c = (char) (one.get() & 0xff);
            if (c == '\n') {
                return result.toString();
            }
            result.append(c);
        }
    }
}
