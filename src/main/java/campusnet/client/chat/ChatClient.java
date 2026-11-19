package campusnet.client.chat;

import campusnet.api.FileTransferClient;
import campusnet.api.ServerInfo;
import campusnet.api.ServerLocator;

import java.io.IOException;

/**
 * Console chat client (M2).
 * I0 stub: prints a banner. Full implementation in milestone I1.
 * Constructor signature is FROZEN — do not change.
 */
public class ChatClient {

    private final ServerLocator locator;
    private final FileTransferClient fileClient;

    public ChatClient(ServerLocator locator, FileTransferClient fileClient) {
        this.locator = locator;
        this.fileClient = fileClient;
    }

    public void connect(ServerInfo server) throws IOException {
        // TODO(m2): connect SocketChannel, start reader thread, run console loop
        System.out.println("CampusNet Client");
        System.out.println("Connecting to " + server.host() + ":" + server.chatPort());
        System.out.println("(stub — not yet implemented)");
    }
}
