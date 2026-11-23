package campusnet.server.file;

import campusnet.api.Config;
import campusnet.api.Grant;
import campusnet.api.Op;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.api.ServerContext;
import campusnet.api.ServerInfo;
import campusnet.api.TokenService;
import campusnet.client.file.FileClient;
import campusnet.common.PropertiesConfig;
import campusnet.server.chat.core.ConnectionRegistryImpl;
import campusnet.server.chat.core.DefaultServerContext;
import campusnet.server.security.InMemoryAuthService;
import campusnet.server.security.InMemoryTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("integration")
class M4UploadDownloadIT {

    private FileServer server;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private TestContext startServer() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("file.port", "0");
        properties.setProperty("file.storageDir", tempDir.resolve("storage").toString());
        properties.setProperty("file.maxBytes", "52428800");
        properties.setProperty("file.idleTimeoutSec", "5");
        properties.setProperty("token.maxPerUser", "100");
        Config config = new PropertiesConfig(properties);
        InMemoryTokenService tokens = new InMemoryTokenService(config);
        ConnectionRegistryImpl registry = new ConnectionRegistryImpl();
        InMemoryAuthService auth = new InMemoryAuthService(config);
        ServerContext context = new DefaultServerContext(config, registry, auth, tokens);
        server = new FileServer(context);
        server.start();
        return new TestContext(context, tokens);
    }

    @Test
    void roundTripsZeroAndOneByteFiles() throws Exception {
        TestContext context = startServer();
        ServerInfo info = new ServerInfo("127.0.0.1", 5000, server.localPort());
        FileClient client = new FileClient();

        for (byte[] data : new byte[][] {new byte[0], new byte[] {42}}) {
            String name = data.length == 0 ? "zero.bin" : "one.bin";
            Path local = tempDir.resolve(name);
            Files.write(local, data);
            String token = context.tokens().issue(
                    new Principal("alice", Role.STUDENT), Op.PUT, name, data.length);

            client.upload(info, token, local);

            Path downloaded = tempDir.resolve(name + ".download");
            String getToken = context.tokens().issue(
                    new Principal("alice", Role.STUDENT), Op.GET, name, 0);
            client.download(info, getToken, downloaded);
            assertArrayEquals(data, Files.readAllBytes(downloaded));
        }
    }

    @Test
    void roundTripsFiveMegabytesAndMatchesSha256() throws Exception {
        TestContext context = startServer();
        byte[] data = new byte[5 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        Path local = tempDir.resolve("five-mb.bin");
        Files.write(local, data);
        String putToken = context.tokens().issue(
                new Principal("alice", Role.STUDENT), Op.PUT, "five-mb.bin", data.length);

        new FileClient().upload(
                new ServerInfo("127.0.0.1", 5000, server.localPort()),
                putToken,
                local);

        Path downloaded = tempDir.resolve("five-mb.bin.download");
        String getToken = context.tokens().issue(
                new Principal("alice", Role.STUDENT), Op.GET, "five-mb.bin", 0);
        new FileClient().download(
                new ServerInfo("127.0.0.1", 5000, server.localPort()),
                getToken,
                downloaded);

        assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(data),
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(downloaded)));
    }

    @Test
    void replayedAndWrongOperationTokensAreRejected() throws Exception {
        TestContext context = startServer();
        ServerInfo info = new ServerInfo("127.0.0.1", 5000, server.localPort());

        Path local = tempDir.resolve("wrong.bin");
        Files.write(local, new byte[] {1, 2, 3});
        String putToken = context.tokens().issue(
                new Principal("alice", Role.STUDENT), Op.PUT, "wrong.bin", 3);
        new FileClient().upload(info, putToken, local);

        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            writeHeader(channel, "GET " + putToken);
            assertEquals("ERR BAD_TOKEN", readLine(channel));
        }

        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            writeHeader(channel, "PUT " + putToken);
            assertEquals("ERR BAD_TOKEN", readLine(channel));
        }

        String getToken = context.tokens().issue(
                new Principal("bob", Role.LECTURER), Op.GET, "wrong.bin", 0);
        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            writeHeader(channel, "PUT " + getToken);
            assertEquals("ERR BAD_TOKEN", readLine(channel));
        }
    }

    @Test
    void concurrentUploadsRemainIndependent() throws Exception {
        TestContext context = startServer();
        ServerInfo info = new ServerInfo("127.0.0.1", 5000, server.localPort());
        int count = 20;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    byte[] data = new byte[4096];
                    java.util.Arrays.fill(data, (byte) index);
                    Path local = tempDir.resolve("local-" + index + ".bin");
                    Files.write(local, data);
                    String name = "concurrent-" + index + ".bin";
                    String token = context.tokens().issue(
                            new Principal("alice", Role.STUDENT), Op.PUT, name, data.length);
                    ready.countDown();
                    ready.await();
                    new FileClient().upload(info, token, local);
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());

        for (int i = 0; i < count; i++) {
            Path stored = tempDir.resolve("storage/concurrent-" + i + ".bin");
            assertTrue(Files.isRegularFile(stored));
            assertEquals(4096, Files.size(stored));
        }
    }

    @Test
    void traversalNameCannotEscapeStorage() throws Exception {
        TestContext context = startServer();
        Principal alice = new Principal("alice", Role.STUDENT);
        String traversal = "../escaped.bin";
        String token = context.tokens().issue(alice, Op.GET, traversal, 0);

        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            writeHeader(channel, "GET " + token);
            assertTrue(readLine(channel).startsWith("ERR "));
        }

        assertFalse(Files.exists(tempDir.resolve("escaped.bin")));
    }

    @Test
    void expiredTokenIsRejectedAndReplayDoesNotCreateFile() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("file.port", "0");
        properties.setProperty("file.storageDir", tempDir.resolve("expired-storage").toString());
        properties.setProperty("file.idleTimeoutSec", "1");
        properties.setProperty("token.ttlSec", "0");
        properties.setProperty("token.maxPerUser", "100");
        Config config = new PropertiesConfig(properties);
        InMemoryTokenService tokens = new InMemoryTokenService(config);
        ServerContext context = new DefaultServerContext(
                config,
                new ConnectionRegistryImpl(),
                new InMemoryAuthService(config),
                tokens);
        server = new FileServer(context);
        server.start();

        String token = tokens.issue(
                new Principal("alice", Role.STUDENT), Op.PUT, "expired.bin", 4);
        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            writeHeader(channel, "PUT " + token);
            ByteBuffer bytes = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            assertEquals("ERR BAD_TOKEN", readLine(channel));
        }

        assertFalse(Files.exists(tempDir.resolve("expired-storage/expired.bin")));
        assertFalse(Files.exists(tempDir.resolve("expired-storage/expired.bin.part")));
    }

    @Test
    void truncatedUploadDeletesPartAndNoFinalFileAppears() throws Exception {
        TestContext context = startServer();
        String token = context.tokens().issue(
                new Principal("alice", Role.STUDENT), Op.PUT, "truncated.bin", 16);

        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            writeHeader(channel, "PUT " + token);
            channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
        }

        Thread.sleep(300);
        assertFalse(Files.exists(tempDir.resolve("storage/truncated.bin")));
        assertFalse(Files.exists(tempDir.resolve("storage/truncated.bin.part")));
    }

    @Test
    void oversizedHeaderIsRejectedAndConnectionCloses() throws Exception {
        startServer();

        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress("127.0.0.1", server.localPort()))) {
            byte[] oversized = ("GET " + "x".repeat(300) + "\n").getBytes(StandardCharsets.UTF_8);
            channel.write(ByteBuffer.wrap(oversized));

            channel.socket().setSoTimeout(2_000);
            ByteBuffer single = ByteBuffer.allocate(1);
            int read = channel.read(single);
            assertEquals(-1, read);
        }
    }

    private void writeHeader(SocketChannel channel, String header) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap((header + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private String readLine(SocketChannel channel) throws IOException {
        ByteBuffer single = ByteBuffer.allocate(1);
        StringBuilder result = new StringBuilder();
        while (true) {
            single.clear();
            int read = channel.read(single);
            if (read == -1) {
                throw new IOException("Server closed connection");
            }
            if (read == 0) {
                continue;
            }
            single.flip();
            char c = (char) (single.get() & 0xff);
            if (c == '\n') {
                return result.toString();
            }
            result.append(c);
        }
    }

    private record TestContext(ServerContext context, InMemoryTokenService tokens) {}
}
