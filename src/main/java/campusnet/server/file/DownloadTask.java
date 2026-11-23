package campusnet.server.file;

import campusnet.api.Grant;
import campusnet.api.Op;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streams one authorized download from flat storage to a file-channel socket.
 */
public final class DownloadTask implements Runnable {

    private static final System.Logger LOG = System.getLogger(DownloadTask.class.getName());

    private final StorageService storage;
    private final SocketChannel channel;
    private final Grant grant;
    private final int timeoutMillis;

    public DownloadTask(StorageService storage, SocketChannel channel,
                        Grant grant, int timeoutMillis) {
        this.storage = storage;
        this.channel = channel;
        this.grant = grant;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void run() {
        try {
            channel.socket().setSoTimeout(timeoutMillis);

            if (grant == null || grant.op() != Op.GET) {
                writeResponse("ERR BAD_TOKEN");
                return;
            }

            Path source = storage.resolveName(grant.fileName());
            if (!Files.isRegularFile(source)) {
                writeResponse("ERR NO_SUCH_FILE");
                return;
            }

            long size = Files.size(source);
            writeResponse("OK " + size);

            try (FileChannel file = FileChannel.open(source, StandardOpenOption.READ)) {
                long transferred = 0;
                while (transferred < size) {
                    long moved = file.transferTo(transferred, size - transferred, channel);
                    if (moved <= 0) {
                        throw new IOException("Download stalled after " + transferred
                                + " of " + size + " bytes");
                    }
                    transferred += moved;
                }
            }

            LOG.log(System.Logger.Level.INFO, "Download complete: {0} ({1} bytes)",
                    grant.fileName(), size);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Download failed: {0}", e.getMessage());
            try {
                writeResponse("ERR BAD_TOKEN");
            } catch (IOException responseError) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not send download error response: {0}",
                        responseError.getMessage());
            }
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not close download socket: {0}", e.getMessage());
            }
        }
    }

    private void writeResponse(String response) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap((response + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }
}
