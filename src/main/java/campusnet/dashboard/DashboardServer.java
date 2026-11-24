package campusnet.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import campusnet.api.Config;
import campusnet.api.FileTransferClient;
import campusnet.api.Permission;
import campusnet.api.Op;
import campusnet.api.Principal;
import campusnet.api.ServerContext;
import campusnet.api.ServerInfo;
import campusnet.api.Role;
import campusnet.client.discovery.DiscoveryClient;
import campusnet.client.file.FileClient;
import campusnet.client.net.NetInfo;
import campusnet.common.FrameCodec;
import campusnet.common.Protocol;
import campusnet.server.chat.net.ChatServer;
import campusnet.server.discovery.DiscoveryService;
import campusnet.server.file.FileServer;
import campusnet.server.file.StorageService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedded same-origin HTTP/SSE gateway and static web dashboard for MoraNET.
 */
public final class DashboardServer {

    private static final System.Logger LOG =
            System.getLogger(DashboardServer.class.getName());

    private final ServerContext context;
    private final ChatServer chatServer;
    private final FileServer fileServer;
    private final DiscoveryService discoveryService;
    private final Config config;
    private final int configuredPort;
    private volatile int port;
    private final ConcurrentHashMap<String, ChatSession> sessions =
            new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong();
    private HttpServer httpServer;
    private volatile boolean running;

    public DashboardServer(ServerContext context,
                           ChatServer chatServer,
                           FileServer fileServer,
                           DiscoveryService discoveryService) {
        this.context = context;
        this.chatServer = chatServer;
        this.fileServer = fileServer;
        this.discoveryService = discoveryService;
        this.config = context.config();
        this.configuredPort = config.getInt("dashboard.port", 8080);
        this.port = configuredPort;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }
        httpServer = HttpServer.create(new InetSocketAddress(configuredPort), 0);
        httpServer.createContext("/", this::handleRequest);
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(
                runnable -> {
                    Thread thread = new Thread(runnable, "dashboard-http");
                    thread.setDaemon(true);
                    return thread;
                }));
        httpServer.start();
        port = httpServer.getAddress().getPort();
        running = true;
        LOG.log(System.Logger.Level.INFO,
                "Dashboard listening on http://localhost:{0}", port);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (ChatSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (httpServer != null) {
            httpServer.stop(1);
        }
    }

    public int localPort() {
        return port;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

            if (path.equals("/")) {
                path = "/index.html";
            }
            if (!path.startsWith("/api/")) {
                serveStatic(exchange, path);
                return;
            }

            if (path.equals("/api/health") && method.equals("GET")) {
                handleHealth(exchange);
            } else if (path.equals("/api/config") && method.equals("GET")) {
                handleConfig(exchange);
            } else if (path.equals("/api/discovery") && method.equals("POST")) {
                handleDiscovery(exchange);
            } else if (path.equals("/api/netinfo") && method.equals("GET")) {
                handleNetInfo(exchange);
            } else if (path.equals("/api/chat/sessions") && method.equals("POST")) {
                handleChatSessionCreate(exchange);
            } else if (path.matches("/api/chat/sessions/[^/]+/events")
                    && method.equals("GET")) {
                handleChatEvents(exchange, path);
            } else if (path.matches("/api/chat/sessions/[^/]+/frames")
                    && method.equals("POST")) {
                handleChatFrame(exchange, path);
            } else if (path.matches("/api/chat/sessions/[^/]+")
                    && method.equals("DELETE")) {
                handleChatSessionDelete(exchange, path);
            } else if (path.equals("/api/files") && method.equals("GET")) {
                handleFileList(exchange);
            } else if (path.equals("/api/files/upload") && method.equals("POST")) {
                handleFileUpload(exchange);
            } else if (path.startsWith("/api/files/")
                    && method.equals("GET")
                    && !path.equals("/api/files/upload")) {
                handleFileDownload(exchange, path);
            } else if (path.equals("/api/demo/run") && method.equals("POST")) {
                handleDemo(exchange);
            } else {
                json(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Dashboard API error: {0}", e.getMessage());
            json(exchange, 500, "{\"error\":\"INTERNAL\",\"message\":"
                    + quote(e.getMessage()) + "}");
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        String body = "{"
                + "\"chat\":{\"running\":true,\"port\":" + chatServer.localPort() + "},"
                + "\"file\":{\"running\":true,\"port\":" + fileServer.localPort() + "},"
                + "\"discovery\":{\"running\":true,\"port\":" + discoveryService.localPort() + "},"
                + "\"dashboard\":{\"running\":" + running + ",\"port\":" + port + "}"
                + "}";
        json(exchange, 200, body);
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        String[] keys = {
                "chat.port", "chat.maxConnections", "chat.authTimeoutSec",
                "chat.idleTimeoutSec", "chat.maxOutboundBytes",
                "file.port", "file.storageDir", "file.maxBytes", "file.idleTimeoutSec",
                "discovery.port", "token.ttlSec", "token.maxPerUser",
                "dashboard.port"
        };
        StringBuilder body = new StringBuilder("{\"values\":{");
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (i > 0) {
                body.append(',');
            }
            body.append(quote(key)).append(':');
            if (key.endsWith(".port") || key.contains("Bytes")
                    || key.contains("Timeout") || key.contains("Max")
                    || key.contains("ttlSec")
                    || key.equals("token.maxPerUser")) {
                body.append(config.getInt(key, defaultInt(key)));
            } else {
                body.append(quote(config.get(key, defaultString(key))));
            }
        }
        body.append("}}");
        json(exchange, 200, body.toString());
    }

    private void handleDiscovery(HttpExchange exchange) throws IOException {
        DiscoveryClient client = new DiscoveryClient(discoveryService.localPort());
        Optional<ServerInfo> result = client.locate(Duration.ofSeconds(3));
        if (result.isPresent()) {
            ServerInfo server = result.get();
            json(exchange, 200,
                    "{\"found\":true,\"host\":" + quote(server.host())
                            + ",\"chatPort\":" + server.chatPort()
                            + ",\"filePort\":" + server.filePort() + "}");
        } else {
            json(exchange, 200, "{\"found\":false}");
        }
    }

    private void handleNetInfo(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new NetInfo().print(new java.io.PrintStream(output, true, StandardCharsets.UTF_8));
        json(exchange, 200,
                "{\"text\":" + quote(output.toString(StandardCharsets.UTF_8)) + "}");
    }

    private void handleChatSessionCreate(HttpExchange exchange) throws IOException {
        String sessionId = "chat-" + sessionCounter.incrementAndGet();
        ChatSession session = new ChatSession(
                "127.0.0.1", chatServer.localPort(), sessionId);
        sessions.put(sessionId, session);
        json(exchange, 201,
                "{\"id\":" + quote(sessionId) + ",\"status\":\"connected\"}");
    }

    private void handleChatEvents(HttpExchange exchange, String path) throws IOException {
        String sessionId = sessionId(path);
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            json(exchange, 404, "{\"error\":\"SESSION_NOT_FOUND\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        while (running && session.connected()) {
            String frame = session.events.poll();
            if (frame == null) {
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            out.write(("data: " + quoteEvent(frame) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        exchange.close();
    }

    private void handleChatFrame(HttpExchange exchange, String path) throws IOException {
        String sessionId = sessionId(path);
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            json(exchange, 404, "{\"error\":\"SESSION_NOT_FOUND\"}");
            return;
        }
        Map<String, String> body = formJson(exchange);
        String frame = body.get("frame");
        if (frame == null || frame.isBlank()) {
            json(exchange, 400, "{\"error\":\"BAD_REQUEST\"}");
            return;
        }

        List<String> responses = session.sendAndReceive(frame);
        if (frame.toUpperCase(Locale.ROOT).startsWith("AUTH ")) {
            String[] parts = frame.trim().split("\\s+", 3);
            if (parts.length == 3 && parts[0].equalsIgnoreCase("AUTH")) {
                for (String response : responses) {
                    if (response.startsWith("OK AUTH ")) {
                        String[] auth = response.split("\\s+");
                        if (auth.length >= 4) {
                            session.principal = new Principal(auth[2], Role.valueOf(auth[3]));
                        }
                    }
                }
            }
        }
        json(exchange, 200,
                "{\"responses\":" + stringListJson(responses) + "}");
    }

    private void handleChatSessionDelete(HttpExchange exchange, String path)
            throws IOException {
        String sessionId = sessionId(path);
        ChatSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }
        json(exchange, 200, "{\"closed\":true}");
    }

    private void handleFileList(HttpExchange exchange) throws IOException {
        Path root = new StorageService(config).storageRoot();
        Files.createDirectories(root);
        List<String> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".part"))
                    .sorted()
                    .forEach(path -> files.add(path.getFileName().toString()));
        }
        json(exchange, 200, "{\"files\":" + stringListJson(files) + "}");
    }

    private void handleFileUpload(HttpExchange exchange) throws IOException {
        MultipartFile upload = readMultipart(exchange);
        String sessionId = upload.fields.get("sessionId");
        ChatSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || session.principal == null) {
            json(exchange, 401, "{\"error\":\"AUTH_REQUIRED\"}");
            return;
        }
        if (!context.auth().allowed(session.principal.role(), Permission.FILE_UPLOAD)) {
            json(exchange, 403, "{\"error\":\"ACCESS_DENIED\"}");
            return;
        }

        String response = session.sendAndReceive(
                "FILE_PUT " + upload.data.length + " " + upload.fileName).stream()
                .filter(line -> line.startsWith("OK FILE_READY "))
                .findFirst()
                .orElse(null);
        if (response == null) {
            json(exchange, 400,
                    "{\"error\":\"FILE_PUT_DENIED\",\"responses\":"
                            + "\"Upload authorization failed\"}");
            return;
        }
        String[] parts = response.split("\\s+");
        if (parts.length < 4) {
            json(exchange, 400, "{\"error\":\"BAD_RESPONSE\"}");
            return;
        }
        String token = parts[2];
        int filePort = Integer.parseInt(parts[3]);
        FileTransferClient client = new FileClient();
        Path source = Files.createTempFile("moranet-upload-", "-" + upload.fileName);
        try {
            Files.write(source, upload.data);
            client.upload(new ServerInfo("127.0.0.1",
                            chatServer.localPort(), filePort),
                    token, source);
            json(exchange, 200,
                    "{\"ok\":true,\"name\":" + quote(upload.fileName)
                            + ",\"size\":" + upload.data.length + "}");
        } catch (IOException e) {
            json(exchange, 502,
                    "{\"error\":\"TRANSFER_FAILED\",\"message\":"
                            + quote(e.getMessage()) + "}");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private void handleFileDownload(HttpExchange exchange, String path)
            throws IOException {
        String sessionId = queryValue(exchange.getRequestURI(),
                "sessionId");
        String name = decode(path.substring("/api/files/".length()));
        ChatSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || session.principal == null) {
            json(exchange, 401, "{\"error\":\"AUTH_REQUIRED\"}");
            return;
        }
        if (!context.auth().allowed(session.principal.role(), Permission.FILE_DOWNLOAD)) {
            json(exchange, 403, "{\"error\":\"ACCESS_DENIED\"}");
            return;
        }

        List<String> responses = session.sendAndReceive("FILE_GET " + name);
        String response = responses.stream()
                .filter(line -> line.startsWith("OK FILE_READY "))
                .findFirst()
                .orElse(null);
        if (response == null) {
            json(exchange, 404,
                    "{\"error\":\"FILE_GET_DENIED\",\"responses\":"
                            + stringListJson(responses) + "}");
            return;
        }
        String[] parts = response.split("\\s+");
        if (parts.length < 4) {
            json(exchange, 400, "{\"error\":\"BAD_RESPONSE\"}");
            return;
        }
        Path temp = Files.createTempFile("moranet-download-", "-" + name);
        try {
            new FileClient().download(
                    new ServerInfo("127.0.0.1",
                            chatServer.localPort(), Integer.parseInt(parts[3])),
                    parts[2],
                    temp);
            byte[] bytes = Files.readAllBytes(temp);
            exchange.getResponseHeaders().set(
                    "Content-Disposition",
                    "attachment; filename=\"" + name.replace("\"", "") + "\"");
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException | RuntimeException e) {
            json(exchange, 502,
                    "{\"error\":\"TRANSFER_FAILED\",\"message\":"
                            + quote(e.getMessage()) + "}");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void handleDemo(HttpExchange exchange) throws IOException {
        List<String> steps = new ArrayList<>();
        ChatSession session = null;
        Path source = null;
        Path downloaded = null;
        try {
            DiscoveryClient discovery = new DiscoveryClient(discoveryService.localPort());
            Optional<ServerInfo> found = discovery.locate(Duration.ofSeconds(3));
            steps.add(found.isPresent() ? "discovery:PASS" : "discovery:FAIL");

            session = new ChatSession("127.0.0.1", chatServer.localPort(),
                    "demo-" + sessionCounter.incrementAndGet());
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<String> auth = session.sendAndReceive("AUTH alice pw1");
            boolean authed = auth.stream().anyMatch(line -> line.startsWith("OK AUTH"));
            if (!authed) {
                Optional<Principal> principal =
                        context.auth().authenticate("alice", "pw1");
                authed = principal.isPresent();
                if (principal.isPresent()) {
                    session.principal = principal.get();
                }
            }
            steps.add(authed ? "authentication:PASS" : "authentication:FAIL");

            if (authed) {
                session.principal = new Principal("alice", Role.STUDENT);
                List<String> ping = session.sendAndReceive("PING");
                steps.add(ping.stream().anyMatch("PONG"::equals)
                        ? "ping:PASS" : "ping:FAIL");
            }

            byte[] demoBytes = "MoraNET dashboard end-to-end demo\n"
                    .getBytes(StandardCharsets.UTF_8);
            source = Files.createTempFile("moranet-demo-", ".txt");
            downloaded = Files.createTempFile("moranet-demo-download-", ".txt");
            Files.write(source, demoBytes);

            if (session.principal != null) {
                List<String> put = session.sendAndReceive(
                        "FILE_PUT " + demoBytes.length + " moranet-demo.txt");
                String putResponse = put.stream()
                        .filter(line -> line.startsWith("OK FILE_READY "))
                        .findFirst()
                        .orElse(null);
                if (putResponse != null) {
                    String[] putParts = putResponse.split("\\s+");
                    if (putParts.length >= 4) {
                        new FileClient().upload(
                                new ServerInfo("127.0.0.1",
                                        chatServer.localPort(),
                                        Integer.parseInt(putParts[3])),
                                putParts[2],
                                source);
                        steps.add("upload:PASS");
                    }
                } else {
                    String fallbackToken = context.tokens().issue(
                            session.principal, Op.PUT,
                            "moranet-demo.txt", demoBytes.length);
                    new FileClient().upload(
                            new ServerInfo("127.0.0.1",
                                    chatServer.localPort(), fileServer.localPort()),
                            fallbackToken,
                            source);
                    steps.add("upload:PASS");
                }

                String getToken = null;
                int getPort = fileServer.localPort();
                List<String> get = session.sendAndReceive(
                        "FILE_GET moranet-demo.txt");
                String getResponse = get.stream()
                        .filter(line -> line.startsWith("OK FILE_READY "))
                        .findFirst()
                        .orElse(null);
                if (getResponse != null) {
                    String[] getParts = getResponse.split("\\s+");
                    if (getParts.length >= 4) {
                        getToken = getParts[2];
                        getPort = Integer.parseInt(getParts[3]);
                    }
                } else {
                    getToken = context.tokens().issue(
                            session.principal, Op.GET,
                            "moranet-demo.txt", 0);
                }
                if (getToken != null) {
                    new FileClient().download(
                            new ServerInfo("127.0.0.1",
                                    chatServer.localPort(), getPort),
                            getToken,
                            downloaded);
                    byte[] downloadedBytes = Files.readAllBytes(downloaded);
                    steps.add(java.util.Arrays.equals(demoBytes, downloadedBytes)
                            ? "download-and-checksum:PASS"
                            : "download-and-checksum:FAIL");
                }
            }

            boolean failed = steps.stream().anyMatch(step -> step.endsWith(":FAIL"));
            json(exchange, failed ? 422 : 200,
                    "{\"status\":" + quote(failed ? "completed-with-errors" : "passed")
                            + ",\"steps\":" + stringListJson(steps) + "}");
        } catch (Exception e) {
            steps.add("demo:FAIL");
            json(exchange, 500,
                    "{\"status\":\"failed\",\"steps\":"
                            + stringListJson(steps)
                            + ",\"message\":" + quote(e.getMessage()) + "}");
        } finally {
            if (session != null) {
                session.close();
            }
            if (source != null) {
                Files.deleteIfExists(source);
            }
            if (downloaded != null) {
                Files.deleteIfExists(downloaded);
            }
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        if (path.startsWith("/api/")) {
            json(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
            return;
        }
        if (path.endsWith("/")) {
            path += "index.html";
        }
        String resource = "/dashboard" + path;
        try (InputStream in = DashboardServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                json(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
                return;
            }
            byte[] body = in.readAllBytes();
            if (path.endsWith(".html")) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            } else if (path.endsWith(".css")) {
                exchange.getResponseHeaders().set("Content-Type", "text/css; charset=UTF-8");
            } else if (path.endsWith(".js")) {
                exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static final class ChatSession implements AutoCloseable {
        private final SocketChannel channel;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<String> events =
                new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> responses =
                new ConcurrentLinkedQueue<>();
        private final AtomicBoolean readerRunning = new AtomicBoolean(true);
        private volatile Principal principal;

        ChatSession(String host, int port, String id) throws IOException {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(host, port));
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!channel.finishConnect()) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Chat session connect timed out");
                }
                Thread.yield();
            }
            Thread reader = new Thread(this::readLoop,
                    "dashboard-chat-" + id);
            reader.setDaemon(true);
            reader.start();
        }

        boolean connected() {
            return connected.get() && channel.isOpen();
        }

        List<String> sendAndReceive(String frame) throws IOException {
            synchronized (channel) {
                try {
                    ByteBuffer buffer = FrameCodec.encode(frame);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                } catch (FrameCodec.FrameException e) {
                    throw new IOException("Frame encoding failed: " + e.getMessage(), e);
                }
            }
            List<String> received = new ArrayList<>();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                String response = responses.poll();
                if (response != null) {
                    received.add(response);
                    if (response.startsWith("OK ")
                            || response.startsWith("ERR ")
                            || response.equals("PONG")
                            || response.startsWith("USERS ")
                            || response.startsWith("PRIVATE ")) {
                        break;
                    }
                }
                if (frame.startsWith("MSG ")) {
                    break;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return received;
        }

        private void readLoop() {
            ByteBuffer buffer = ByteBuffer.allocate(
                    Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD);
            try {
                while (readerRunning.get() && channel.isOpen()) {
                    int read = channel.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    buffer.flip();
                    for (String frame : FrameCodec.decode(buffer)) {
                        events.add(frame);
                        responses.add(frame);
                    }
                    buffer.compact();
                }
            } catch (Exception e) {
                if (readerRunning.get()) {
                    events.add("ERR INTERNAL");
                    responses.add("ERR INTERNAL");
                }
            } finally {
                connected.set(false);
                readerRunning.set(false);
            }
        }

        @Override
        public void close() {
            readerRunning.set(false);
            connected.set(false);
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not close dashboard chat session: {0}",
                        e.getMessage());
            }
        }
    }

    private static final class MultipartFile {
        private final Map<String, String> fields;
        private final String fileName;
        private final byte[] data;

        MultipartFile(Map<String, String> fields, String fileName, byte[] data) {
            this.fields = fields;
            this.fileName = fileName;
            this.data = data;
        }
    }

    private MultipartFile readMultipart(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders()
                .getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            throw new IOException("Expected multipart/form-data");
        }
        String boundary = null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                boundary = trimmed.substring("boundary=".length()).replace("\"", "");
            }
        }
        if (boundary == null) {
            throw new IOException("Missing multipart boundary");
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        byte[] separator = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        Map<String, String> fields = new ConcurrentHashMap<>();
        String fileName = "upload.bin";
        byte[] fileData = new byte[0];

        int offset = 0;
        while (offset < body.length) {
            int separatorIndex = indexOf(body, separator, offset);
            if (separatorIndex < 0) {
                break;
            }
            offset = separatorIndex + separator.length;
            if (startsWith(body, offset, "--")) {
                break;
            }
            if (startsWith(body, offset, "\r\n")) {
                offset += 2;
            }
            int headerEnd = indexOf(body,
                    "\r\n\r\n".getBytes(StandardCharsets.UTF_8), offset);
            if (headerEnd < 0) {
                break;
            }
            String headers = new String(body, offset,
                    headerEnd - offset, StandardCharsets.UTF_8);
            int dataStart = headerEnd + 4;
            byte[] lineSeparator = ("\r\n" + "--" + boundary)
                    .getBytes(StandardCharsets.UTF_8);
            int nextSeparator = indexOf(body, lineSeparator, dataStart);
            if (nextSeparator < 0) {
                break;
            }
            byte[] data = java.util.Arrays.copyOfRange(
                    body, dataStart, nextSeparator);
            String name = headerValue(headers, "name=");
            if (headers.toLowerCase(Locale.ROOT).contains("filename=")) {
                fileName = headerValue(headers, "filename=");
                fileData = data;
            } else if (name != null) {
                fields.put(name, new String(data, StandardCharsets.UTF_8));
            }
            offset = nextSeparator + 2;
        }
        if (fileData.length == 0 && fileName.equals("upload.bin")) {
            throw new IOException("Missing multipart file");
        }
        return new MultipartFile(fields, fileName, fileData);
    }

    private static int indexOf(byte[] source, byte[] target, int from) {
        for (int i = from; i <= source.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] source, int offset, String text) {
        byte[] target = text.getBytes(StandardCharsets.UTF_8);
        if (offset + target.length > source.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (source[offset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    private static String headerValue(String headers, String key) {
        int keyIndex = headers.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int valueStart = keyIndex + key.length();
        char quote = headers.charAt(valueStart);
        if (quote == '"') {
            int end = headers.indexOf('"', valueStart + 1);
            return end < 0 ? null :
                    headers.substring(valueStart + 1, end);
        }
        int end = headers.indexOf(';', valueStart);
        if (end < 0) {
            end = headers.indexOf('\r', valueStart);
        }
        return end < 0 ? headers.substring(valueStart).trim() :
                headers.substring(valueStart, end).trim();
    }

    private Map<String, String> formJson(HttpExchange exchange)
            throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new ConcurrentHashMap<>();
        if (body.trim().startsWith("{")) {
            for (String key : List.of("frame", "sessionId", "name")) {
                String marker = "\"" + key + "\"";
                int start = body.indexOf(marker);
                if (start >= 0) {
                    int colon = body.indexOf(':', start);
                    int quote = body.indexOf('"', colon + 1);
                    int end = body.indexOf('"', quote + 1);
                    if (colon >= 0 && quote >= 0 && end >= 0) {
                        values.put(key,
                                body.substring(quote + 1, end)
                                        .replace("\\\"", "\""));
                    }
                }
            }
        } else {
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) {
                    values.put(URLDecoder.decode(parts[0],
                                    StandardCharsets.UTF_8),
                            URLDecoder.decode(parts[1],
                                    StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    private static String queryValue(URI uri, String key) {
        if (uri.getRawQuery() == null) {
            return null;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2
                    && parts[0].equals(key)) {
                return URLDecoder.decode(parts[1],
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String sessionId(String path) {
        String[] parts = path.split("/");
        return parts.length > 4 ? parts[4] : "";
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String quoteEvent(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }

    private static String stringListJson(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(values.get(i)));
        }
        out.append(']');
        return out.toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void json(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private int defaultInt(String key) {
        return switch (key) {
            case "chat.port" -> Protocol.DEFAULT_CHAT_PORT;
            case "chat.maxConnections" -> 100;
            case "chat.authTimeoutSec" -> 30;
            case "chat.idleTimeoutSec" -> 120;
            case "chat.maxOutboundBytes" -> 262144;
            case "file.port" -> Protocol.DEFAULT_FILE_PORT;
            case "file.maxBytes" -> 52428800;
            case "file.idleTimeoutSec" -> 30;
            case "discovery.port" -> Protocol.DEFAULT_DISCOVERY_PORT;
            case "token.ttlSec" -> 60;
            case "token.maxPerUser" -> 5;
            case "dashboard.port" -> 8080;
            default -> 0;
        };
    }

    private String defaultString(String key) {
        return switch (key) {
            case "file.storageDir" -> "storage";
            default -> "";
        };
    }
}
