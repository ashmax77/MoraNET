package campusnet.client;

import campusnet.api.ServerInfo;
import campusnet.client.chat.ChatClient;
import campusnet.client.discovery.DiscoveryClient;
import campusnet.client.file.FileClient;
import campusnet.common.Protocol;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * CampusNet client entry point (M2).
 *
 * Usage:
 *   java -cp target/classes campusnet.client.ClientMain
 *   java -cp target/classes campusnet.client.ClientMain host:port
 *
 * DO NOT EDIT after the skeleton PR merges (I0). This file is owned by M1 and frozen.
 */
public class ClientMain {

    public static void main(String[] args) throws IOException {
        ServerInfo server = resolveServer(args);

        FileClient fileClient = new FileClient();
        DiscoveryClient discoveryClient = new DiscoveryClient(Protocol.DEFAULT_DISCOVERY_PORT);

        ChatClient client = new ChatClient(discoveryClient, fileClient);
        client.connect(server);
    }

    private static ServerInfo resolveServer(String[] args) throws IOException {
        if (args.length > 0) {
            // Manual host:port supplied on the command line
            String[] parts = args[0].split(":");
            if (parts.length == 2) {
                return new ServerInfo(parts[0],
                        Integer.parseInt(parts[1]),
                        Protocol.DEFAULT_FILE_PORT);
            }
        }

        // Try UDP discovery
        DiscoveryClient disco = new DiscoveryClient(Protocol.DEFAULT_DISCOVERY_PORT);
        try {
            Optional<ServerInfo> found = disco.locate(Duration.ofSeconds(3));
            if (found.isPresent()) {
                System.out.println("Discovered server: " + found.get().host()
                        + ":" + found.get().chatPort());
                return found.get();
            }
        } catch (IOException e) {
            System.out.println("Discovery failed: " + e.getMessage());
        }

        // Fall back to localhost
        System.out.println("Server not found via discovery. Connecting to localhost:"
                + Protocol.DEFAULT_CHAT_PORT);
        return new ServerInfo("localhost", Protocol.DEFAULT_CHAT_PORT, Protocol.DEFAULT_FILE_PORT);
    }
}
