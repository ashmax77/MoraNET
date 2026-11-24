package campusnet.dashboard;

import campusnet.api.Config;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class DashboardServerIT {

    private ChatServer chatServer;
    private FileServer fileServer;
    private DiscoveryService discoveryService;
    private DashboardServer dashboardServer;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServices() {
        if (dashboardServer != null) {
            dashboardServer.stop();
        }
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

    @Test
    void dashboardRoutesAndE2EDemoWork() throws Exception {
        int chatPort = findFreeTcpPort();
        int filePort = findFreeTcpPort();
        int discoveryPort = findFreeUdpPort();
        int dashboardPort = findFreeTcpPort();

        Properties properties = new Properties();
        properties.setProperty("chat.port", Integer.toString(chatPort));
        properties.setProperty("chat.maxConnections", "250");
        properties.setProperty("chat.authTimeoutSec", "30");
        properties.setProperty("chat.idleTimeoutSec", "120");
        properties.setProperty("chat.maxOutboundBytes", "262144");
        properties.setProperty("file.port", Integer.toString(filePort));
        properties.setProperty("file.storageDir",
                tempDir.resolve("storage").toString());
        properties.setProperty("file.maxBytes", "52428800");
        properties.setProperty("file.idleTimeoutSec", "5");
        properties.setProperty("discovery.port",
                Integer.toString(discoveryPort));
        properties.setProperty("token.ttlSec", "60");
        properties.setProperty("token.maxPerUser", "100");
        properties.setProperty("dashboard.port",
                Integer.toString(dashboardPort));

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
        dashboardServer =
                new DashboardServer(context, chatServer,
                        fileServer, discoveryService);

        chatServer.start();
        fileServer.start();
        discoveryService.start();
        dashboardServer.start();

        String base = "http://127.0.0.1:" + dashboardServer.localPort();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> index = client.send(
                HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("MoraNET"));

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"chat\""));

        HttpResponse<String> configResponse = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/config")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, configResponse.statusCode());
        assertTrue(configResponse.body().contains("chat.maxConnections"));

        HttpResponse<String> netInfo = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/netinfo")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, netInfo.statusCode());
        assertTrue(netInfo.body().contains("Interface"));

        HttpResponse<String> discovery = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/discovery"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, discovery.statusCode());
        assertTrue(discovery.body().contains("\"found\":true"));

        HttpResponse<String> sessionResponse = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/chat/sessions"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, sessionResponse.statusCode());
        String sessionId = sessionResponse.body().split("\"")[3];

        HttpResponse<String> authResponse = client.send(
                HttpRequest.newBuilder(
                        URI.create(base + "/api/chat/sessions/"
                                + sessionId + "/frames"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"frame\":\"AUTH alice pw1\"}",
                                StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authResponse.statusCode());
        assertTrue(authResponse.body().contains("OK AUTH alice STUDENT"));

        HttpResponse<String> pingResponse = client.send(
                HttpRequest.newBuilder(
                        URI.create(base + "/api/chat/sessions/"
                                + sessionId + "/frames"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"frame\":\"PING\"}",
                                StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, pingResponse.statusCode());
        assertTrue(pingResponse.body().contains("PONG"));

        byte[] uploadBytes = "dashboard-upload\n".getBytes(StandardCharsets.UTF_8);
        String boundary = "MoraNETBoundary";
        String multipart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"sessionId\"\r\n\r\n"
                + sessionId + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; "
                + "filename=\"dashboard.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n";
        byte[] prefix = multipart.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] multipartBody = new byte[prefix.length + uploadBytes.length
                + suffix.length];
        System.arraycopy(prefix, 0, multipartBody, 0, prefix.length);
        System.arraycopy(uploadBytes, 0, multipartBody,
                prefix.length, uploadBytes.length);
        System.arraycopy(suffix, 0, multipartBody,
                prefix.length + uploadBytes.length, suffix.length);

        HttpResponse<String> upload = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/files/upload"))
                        .header("Content-Type",
                                "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, upload.statusCode(),
                "Upload response: " + upload.body());
        assertTrue(upload.body().contains("dashboard.txt"));

        HttpResponse<String> fileList = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/files")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fileList.statusCode());
        assertTrue(fileList.body().contains("\"files\""));
        assertTrue(fileList.body().contains("dashboard.txt"));

        HttpResponse<byte[]> download = client.send(
                HttpRequest.newBuilder(
                        URI.create(base + "/api/files/dashboard.txt"
                                + "?sessionId=" + sessionId))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertTrue(java.util.Arrays.equals(uploadBytes, download.body()));

        HttpResponse<String> demo = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/demo/run"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, demo.statusCode(),
                "Demo response: " + demo.body());
        assertTrue(demo.body().contains("download-and-checksum:PASS"),
                "Demo response: " + demo.body());
    }

    private int findFreeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private int findFreeUdpPort() throws Exception {
        try (java.net.DatagramSocket socket =
                     new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
