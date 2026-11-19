package campusnet.client.chat;

import campusnet.api.FileTransferClient;
import campusnet.api.ServerInfo;
import campusnet.api.ServerLocator;
import campusnet.common.FrameCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class ChatClientIT {

    private ServerSocketChannel serverChannel;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        serverPort = serverChannel.socket().getLocalPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
    }

    @Test
    @DisplayName("ChatClient connects, exchanges PING/PONG and disconnects on /quit")
    void testPingAndQuit() throws Exception {
        String userInput = "/ping\n/quit\n";
        ByteArrayInputStream in = new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        ServerLocator mockLocator = timeout -> Optional.empty();
        FileTransferClient mockFileClient = new FileTransferClient() {
            @Override
            public void upload(ServerInfo server, String token, Path file) {}

            @Override
            public void download(ServerInfo server, String token, Path destination) {}
        };

        ChatClient client = new ChatClient(mockLocator, mockFileClient, in, ps);

        CountDownLatch serverDone = new CountDownLatch(1);
        AtomicReference<String> receivedPing = new AtomicReference<>();
        AtomicReference<String> receivedQuit = new AtomicReference<>();

        Thread mockServerThread = new Thread(() -> {
            try (SocketChannel clientSocket = serverChannel.accept()) {
                MockConnection conn = new MockConnection(clientSocket);
                conn.send("WELCOME campusnet/1");

                String frame1 = conn.receive();
                receivedPing.set(frame1);

                conn.send("PONG");

                String frame2 = conn.receive();
                receivedQuit.set(frame2);

                conn.send("OK BYE");
            } catch (Exception ignored) {
            } finally {
                serverDone.countDown();
            }
        });
        mockServerThread.start();

        ServerInfo serverInfo = new ServerInfo("127.0.0.1", serverPort, 5001);
        client.connect(serverInfo);

        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        assertEquals("PING", receivedPing.get());
        assertEquals("QUIT", receivedQuit.get());

        String consoleOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("Connected: campusnet/1"));
        assertTrue(consoleOutput.contains("[Server] PONG"));
        assertFalse(client.isRunning());
    }

    @Test
    @DisplayName("ChatClient handles login flow and broadcasts")
    void testLoginAndBroadcast() throws Exception {
        String userInput = "/login alice pass123\nHello world\n/quit\n";
        ByteArrayInputStream in = new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        ServerLocator mockLocator = timeout -> Optional.empty();
        FileTransferClient mockFileClient = new FileTransferClient() {
            @Override
            public void upload(ServerInfo server, String token, Path file) {}

            @Override
            public void download(ServerInfo server, String token, Path destination) {}
        };

        ChatClient client = new ChatClient(mockLocator, mockFileClient, in, ps);

        CountDownLatch serverDone = new CountDownLatch(1);

        Thread mockServerThread = new Thread(() -> {
            try (SocketChannel clientSocket = serverChannel.accept()) {
                MockConnection conn = new MockConnection(clientSocket);
                conn.send("WELCOME campusnet/1");

                String authMsg = conn.receive();
                if ("AUTH alice pass123".equals(authMsg)) {
                    conn.send("OK AUTH alice STUDENT");
                }

                String chatMsg = conn.receive();
                if ("MSG Hello world".equals(chatMsg)) {
                    conn.send("MSG alice: Hello world");
                }

                conn.receive(); // QUIT
                conn.send("OK BYE");
            } catch (Exception ignored) {
            } finally {
                serverDone.countDown();
            }
        });
        mockServerThread.start();

        ServerInfo serverInfo = new ServerInfo("127.0.0.1", serverPort, 5001);
        client.connect(serverInfo);

        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        String consoleOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("Logged in as 'alice' with role [STUDENT]"));
        assertTrue(consoleOutput.contains("alice: Hello world"));
    }

    @Test
    @DisplayName("ChatClient orchestrates /put by requesting token and invoking FileTransferClient")
    void testPutFileOrchestration(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("sample.txt");
        Files.writeString(testFile, "test file content");
        long fileSize = Files.size(testFile);
        String fileName = testFile.getFileName().toString();

        String userInput = "/put \"" + testFile.toAbsolutePath() + "\"\n/quit\n";
        ByteArrayInputStream in = new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        ServerLocator mockLocator = timeout -> Optional.empty();
        AtomicBoolean uploadInvoked = new AtomicBoolean(false);
        AtomicReference<String> uploadToken = new AtomicReference<>();

        FileTransferClient mockFileClient = new FileTransferClient() {
            @Override
            public void upload(ServerInfo server, String token, Path file) {
                uploadInvoked.set(true);
                uploadToken.set(token);
                assertEquals(testFile, file);
                assertEquals(5002, server.filePort());
            }

            @Override
            public void download(ServerInfo server, String token, Path destination) {}
        };

        ChatClient client = new ChatClient(mockLocator, mockFileClient, in, ps);

        CountDownLatch serverDone = new CountDownLatch(1);
        Thread mockServerThread = new Thread(() -> {
            try (SocketChannel clientSocket = serverChannel.accept()) {
                MockConnection conn = new MockConnection(clientSocket);
                conn.send("WELCOME campusnet/1");

                String putMsg = conn.receive();
                assertEquals("FILE_PUT " + fileSize + " " + fileName, putMsg);

                conn.send("OK FILE_READY test-token-123 5002");

                conn.receive(); // QUIT
                conn.send("OK BYE");
            } catch (Exception ignored) {
            } finally {
                serverDone.countDown();
            }
        });
        mockServerThread.start();

        ServerInfo serverInfo = new ServerInfo("127.0.0.1", serverPort, 5001);
        client.connect(serverInfo);

        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        assertTrue(uploadInvoked.get());
        assertEquals("test-token-123", uploadToken.get());
        String consoleOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("Upload authorized"));
        assertTrue(consoleOutput.contains("Transfer complete"));
    }

    @Test
    @DisplayName("ChatClient orchestrates /get by requesting token and invoking FileTransferClient")
    void testGetFileOrchestration(@TempDir Path tempDir) throws Exception {
        Path destFile = tempDir.resolve("downloaded.pdf");

        String userInput = "/get lecture.pdf \"" + destFile.toAbsolutePath() + "\"\n/quit\n";
        ByteArrayInputStream in = new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        ServerLocator mockLocator = timeout -> Optional.empty();
        AtomicBoolean downloadInvoked = new AtomicBoolean(false);
        AtomicReference<String> downloadToken = new AtomicReference<>();

        FileTransferClient mockFileClient = new FileTransferClient() {
            @Override
            public void upload(ServerInfo server, String token, Path file) {}

            @Override
            public void download(ServerInfo server, String token, Path destination) {
                downloadInvoked.set(true);
                downloadToken.set(token);
                assertEquals(destFile, destination);
                assertEquals(5002, server.filePort());
            }
        };

        ChatClient client = new ChatClient(mockLocator, mockFileClient, in, ps);

        CountDownLatch serverDone = new CountDownLatch(1);
        Thread mockServerThread = new Thread(() -> {
            try (SocketChannel clientSocket = serverChannel.accept()) {
                MockConnection conn = new MockConnection(clientSocket);
                conn.send("WELCOME campusnet/1");

                String getMsg = conn.receive();
                assertEquals("FILE_GET lecture.pdf", getMsg);

                conn.send("OK FILE_READY get-token-456 5002");

                conn.receive(); // QUIT
                conn.send("OK BYE");
            } catch (Exception ignored) {
            } finally {
                serverDone.countDown();
            }
        });
        mockServerThread.start();

        ServerInfo serverInfo = new ServerInfo("127.0.0.1", serverPort, 5001);
        client.connect(serverInfo);

        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        assertTrue(downloadInvoked.get());
        assertEquals("get-token-456", downloadToken.get());
        String consoleOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("Download authorized"));
    }

    private static class MockConnection {
        private final SocketChannel channel;
        private final ByteBuffer buf = ByteBuffer.allocate(8192);
        private final Queue<String> incoming = new ArrayDeque<>();

        MockConnection(SocketChannel channel) {
            this.channel = channel;
        }

        String receive() throws Exception {
            while (incoming.isEmpty()) {
                int n = channel.read(buf);
                if (n == -1) {
                    throw new IOException("EOF");
                }
                buf.flip();
                List<String> frames = FrameCodec.decode(buf);
                incoming.addAll(frames);
                buf.compact();
            }
            return incoming.poll();
        }

        void send(String payload) throws Exception {
            ByteBuffer b = FrameCodec.encode(payload);
            while (b.hasRemaining()) {
                channel.write(b);
            }
        }
    }
}
