package campusnet.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Performs file upload and download over the file channel (TCP 5001).
 * FileClient in campusnet.client.file implements this.
 * FROZEN — do not change without following the contract change procedure.
 */
public interface FileTransferClient {
    /**
     * Upload a local file to the server.
     * @param server    the server's address and file port
     * @param token     the one-time token from OK FILE_READY
     * @param file      path to the local file to upload
     */
    void upload(ServerInfo server, String token, Path file) throws IOException;

    /**
     * Download a file from the server.
     * @param server      the server's address and file port
     * @param token       the one-time token from OK FILE_READY
     * @param destination local path to write the downloaded file to
     */
    void download(ServerInfo server, String token, Path destination) throws IOException;
}
