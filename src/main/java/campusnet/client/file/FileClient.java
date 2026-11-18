package campusnet.client.file;

import campusnet.api.FileTransferClient;
import campusnet.api.ServerInfo;

import java.io.IOException;
import java.nio.file.Path;

/**
 * File transfer client (M4).
 * I0 stub: both methods throw UnsupportedOperationException.
 * Constructor signature is FROZEN — do not change.
 */
public class FileClient implements FileTransferClient {

    public FileClient() {}

    @Override
    public void upload(ServerInfo server, String token, Path file) throws IOException {
        // TODO(m4): connect to server.filePort(), send "PUT token\n", then file bytes
        throw new UnsupportedOperationException("FileClient.upload not yet implemented");
    }

    @Override
    public void download(ServerInfo server, String token, Path destination) throws IOException {
        // TODO(m4): connect to server.filePort(), send "GET token\n", receive bytes
        throw new UnsupportedOperationException("FileClient.download not yet implemented");
    }
}
