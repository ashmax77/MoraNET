package campusnet.client.chat;

import campusnet.api.FileTransferClient;
import campusnet.api.ServerInfo;
import campusnet.api.ServerLocator;
import campusnet.client.net.NetInfo;
import campusnet.common.FrameCodec;
import campusnet.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Console chat client (M2).
 *
 * Connects to the CampusNet ChatServer on TCP port 5000 via a SocketChannel.
 * Runs an asynchronous inbound frame reader thread alongside an interactive
 * console loop reading user commands.
 */
public class ChatClient {

    private final ServerLocator locator;
    private final FileTransferClient fileClient;
    private final NetInfo netInfo;
    private final CommandParser parser;
    private final InputStream in;
    private final PrintStream out;

    private SocketChannel channel;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    // Used to await server response for FILE_PUT and FILE_GET
    private volatile CompletableFuture<String> pendingFileFuture;

    /**
     * Creates a new ChatClient with standard I/O (System.in and System.out).
     * Constructor signature is FROZEN by Section 4.3 of ARCHITECTURE.md.
     *
     * @param locator the discovery server locator
     * @param fileClient the client responsible for file transfer on port 5001
     */
    public ChatClient(ServerLocator locator, FileTransferClient fileClient) {
        this(locator, fileClient, System.in, System.out);
    }

    /**
     * Overloaded constructor for testing with custom input/output streams.
     *
     * @param locator the discovery server locator
     * @param fileClient the client responsible for file transfer on port 5001
     * @param in the input stream to read console commands from
     * @param out the print stream to render console output to
     */
    public ChatClient(ServerLocator locator, FileTransferClient fileClient, InputStream in, PrintStream out) {
        this.locator = locator;
        this.fileClient = fileClient;
        this.in = in;
        this.out = out;
        this.netInfo = new NetInfo();
        this.parser = new CommandParser();
    }

    /**
     * Connects to the chat server and enters the interactive console loop.
     * Blocks until the user enters /quit or the connection is terminated.
     *
     * @param server the target server host and port information
     * @throws IOException if network connection fails
     */
    public void connect(ServerInfo server) throws IOException {
        println("Connecting to CampusNet server at " + server.host() + ":" + server.chatPort() + "...");

        channel = SocketChannel.open();
        channel.configureBlocking(true);
        try {
            channel.connect(new InetSocketAddress(server.host(), server.chatPort()));
        } catch (java.net.ConnectException e) {
            println("[Connection Error] Unable to connect to " + server.host() + ":" + server.chatPort()
                    + " (Connection refused). Please ensure ServerMain is running.");
            channel.close();
            return;
        }

        running.set(true);

        // Start inbound reader thread
        readerThread = new Thread(this::readInboundFrames, "chat-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Run interactive console loop on this thread
        try {
            runConsoleLoop(server);
        } finally {
            disconnect();
        }
    }

    /**
     * Inbound frame reader loop executed on a dedicated background thread.
     */
    private void readInboundFrames() {
        ByteBuffer buf = ByteBuffer.allocate(Protocol.MAX_PAYLOAD * 2);

        try {
            while (running.get() && channel.isOpen()) {
                int bytesRead = channel.read(buf);
                if (bytesRead == -1) {
                    println("\n[Notice] Server closed the connection.");
                    break;
                }

                buf.flip();
                List<String> frames;
                try {
                    frames = FrameCodec.decode(buf);
                } catch (FrameCodec.FrameException e) {
                    println("\n[Error] Failed to decode incoming frame: " + e.getMessage());
                    break;
                }
                buf.compact();

                for (String frame : frames) {
                    handleIncomingFrame(frame);
                }
            }
        } catch (ClosedChannelException e) {
            // Expected during clean shutdown
        } catch (IOException e) {
            if (running.get()) {
                println("\n[Network Error] Connection lost: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * Formats and displays incoming protocol frames.
     * Also signals any waiting file transfer coordination futures.
     */
    private void handleIncomingFrame(String frame) {
        // Check if an active file transfer command is awaiting a response
        CompletableFuture<String> fileFuture = pendingFileFuture;
        if (fileFuture != null && !fileFuture.isDone()) {
            if (frame.startsWith("OK FILE_READY") || frame.startsWith("ERR ")) {
                fileFuture.complete(frame);
            }
        }

        // Format user-friendly console display
        if (frame.startsWith("WELCOME ")) {
            println("[Server] Connected: " + frame.substring(8) + ". Type /help for commands or /login <user> <pass>.");
        } else if (frame.startsWith("OK AUTH ")) {
            String[] parts = frame.split("\\s+");
            String user = parts.length > 2 ? parts[2] : "";
            String role = parts.length > 3 ? parts[3] : "";
            println("[Auth] Logged in as '" + user + "' with role [" + role + "].");
        } else if (frame.startsWith("JOIN ")) {
            println("[Notice] User '" + frame.substring(5).trim() + "' joined.");
        } else if (frame.startsWith("LEAVE ")) {
            println("[Notice] User '" + frame.substring(6).trim() + "' left.");
        } else if (frame.startsWith("MSG ")) {
            // Broadcast message format: "MSG user: text"
            println(frame.substring(4));
        } else if (frame.startsWith("PRIVATE from ")) {
            // Private message format: "PRIVATE from user: text"
            println("[PM] " + frame.substring(13));
        } else if (frame.equals("OK PRIVATE_SENT")) {
            println("[PM] Message delivered.");
        } else if (frame.startsWith("USERS ")) {
            println("[Online Users] " + frame.substring(6));
        } else if (frame.equals("PONG")) {
            println("[Server] PONG");
        } else if (frame.equals("OK BYE")) {
            println("[Server] Disconnected.");
        } else if (frame.startsWith("ERR ")) {
            println("[Error] " + frame.substring(4));
        } else {
            println("[Server] " + frame);
        }
    }

    /**
     * Interactive console loop reading user commands.
     */
    private void runConsoleLoop(ServerInfo server) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

        while (running.get()) {
            String line;
            try {
                line = reader.readLine();
                if (line == null) {
                    break; // EOF on input
                }
            } catch (IOException e) {
                break;
            }

            CommandParser.ParsedCommand cmd = parser.parse(line);
            if (cmd.isEmpty()) {
                continue;
            }

            if (cmd.isInvalid()) {
                println("[Command Error] " + cmd.errorMessage());
                continue;
            }

            try {
                switch (cmd.type()) {
                    case HELP -> println(CommandParser.HELP_TEXT);
                    case INFO -> netInfo.print(out);
                    case DISCOVER -> handleDiscover();
                    case PUT -> handlePut(server, cmd.primaryArg());
                    case GET -> handleGet(server, cmd.primaryArg(), cmd.secondaryArg());
                    case QUIT -> {
                        sendFrame(cmd.wirePayload());
                        try {
                            if (readerThread != null) {
                                readerThread.join(2000);
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        running.set(false);
                        return;
                    }
                    default -> {
                        if (cmd.isWireFrame()) {
                            sendFrame(cmd.wirePayload());
                        }
                    }
                }
            } catch (FrameCodec.FrameException e) {
                println("[Error] Message exceeds allowed size limits: " + e.getMessage());
            } catch (IOException e) {
                println("[Error] Failed to send message: " + e.getMessage());
                break;
            }
        }
    }

    /**
     * Executes local discovery check.
     */
    private void handleDiscover() {
        println("[Discovery] Searching for CampusNet server on LAN...");
        try {
            Optional<ServerInfo> discovered = locator.locate(Duration.ofSeconds(3));
            if (discovered.isPresent()) {
                ServerInfo info = discovered.get();
                println("[Discovery] Discovered server at " + info.host() + " (Chat: " + info.chatPort() + ", File: " + info.filePort() + ")");
            } else {
                println("[Discovery] No server responded within timeout.");
            }
        } catch (IOException e) {
            println("[Discovery Failed] " + e.getMessage());
        }
    }

    /**
     * Orchestrates /put file upload.
     */
    private void handlePut(ServerInfo server, String pathStr) {
        Path path = Paths.get(pathStr);
        if (!Files.exists(path) || Files.isDirectory(path)) {
            println("[Upload Error] File does not exist or is a directory: " + pathStr);
            return;
        }

        String fileName = path.getFileName().toString();
        if (!Protocol.isValidFileName(fileName)) {
            println("[Upload Error] Invalid file name: " + fileName);
            return;
        }

        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            println("[Upload Error] Failed to read file size: " + e.getMessage());
            return;
        }

        pendingFileFuture = new CompletableFuture<>();
        try {
            sendFrame("FILE_PUT " + size + " " + fileName);
            String response = pendingFileFuture.get(5, TimeUnit.SECONDS);

            if (response.startsWith("OK FILE_READY ")) {
                String[] parts = response.split("\\s+");
                if (parts.length >= 4) {
                    String token = parts[2];
                    int filePort = Integer.parseInt(parts[3]);
                    ServerInfo fileServer = new ServerInfo(server.host(), server.chatPort(), filePort);

                    println("[Upload] Upload authorized. Transferring " + fileName + " (" + size + " bytes)...");
                    fileClient.upload(fileServer, token, path);
                    println("[Upload] Transfer complete for " + fileName);
                } else {
                    println("[Upload Error] Malformed server response: " + response);
                }
            } else {
                println("[Upload Denied] " + response);
            }
        } catch (TimeoutException e) {
            println("[Upload Error] Server did not respond to FILE_PUT within 5 seconds.");
        } catch (InterruptedException | ExecutionException | IOException | FrameCodec.FrameException e) {
            println("[Upload Error] " + e.getMessage());
        } finally {
            pendingFileFuture = null;
        }
    }

    /**
     * Orchestrates /get file download.
     */
    private void handleGet(ServerInfo server, String fileName, String destStr) {
        if (!Protocol.isValidFileName(fileName)) {
            println("[Download Error] Invalid file name: " + fileName);
            return;
        }

        Path destination = destStr != null ? Paths.get(destStr) : Paths.get(fileName);

        pendingFileFuture = new CompletableFuture<>();
        try {
            sendFrame("FILE_GET " + fileName);
            String response = pendingFileFuture.get(5, TimeUnit.SECONDS);

            if (response.startsWith("OK FILE_READY ")) {
                String[] parts = response.split("\\s+");
                if (parts.length >= 4) {
                    String token = parts[2];
                    int filePort = Integer.parseInt(parts[3]);
                    ServerInfo fileServer = new ServerInfo(server.host(), server.chatPort(), filePort);

                    println("[Download] Download authorized. Receiving " + fileName + "...");
                    fileClient.download(fileServer, token, destination);
                    println("[Download] File saved to " + destination.toAbsolutePath());
                } else {
                    println("[Download Error] Malformed server response: " + response);
                }
            } else {
                println("[Download Denied] " + response);
            }
        } catch (TimeoutException e) {
            println("[Download Error] Server did not respond to FILE_GET within 5 seconds.");
        } catch (InterruptedException | ExecutionException | IOException | FrameCodec.FrameException e) {
            println("[Download Error] " + e.getMessage());
        } finally {
            pendingFileFuture = null;
        }
    }

    /**
     * Encodes and transmits a length-prefixed protocol frame to the server.
     */
    private void sendFrame(String payload) throws IOException, FrameCodec.FrameException {
        ByteBuffer buf = FrameCodec.encode(payload);
        synchronized (writeLock) {
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
        }
    }

    /**
     * Synchronized console output to prevent interleaving between reader thread and typing.
     */
    private void println(String message) {
        synchronized (out) {
            out.println(message);
        }
    }

    /**
     * Disconnects the socket channel and stops reader threads.
     */
    public void disconnect() {
        running.set(false);
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException ignored) {}
        }
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }
    }

    /**
     * Returns whether the client is currently running and connected.
     */
    public boolean isRunning() {
        return running.get() && channel != null && channel.isOpen();
    }
}
