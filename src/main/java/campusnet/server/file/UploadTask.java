package campusnet.server.file;

import campusnet.api.Grant;
import campusnet.api.Op;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;

/**
 * Streams one authorized upload from a file-channel socket into flat storage.
 */
public final class UploadTask implements Runnable {

    private static final System.Logger LOG = System.getLogger(UploadTask.class.getName());

    private final StorageService storage;
    private final SocketChannel channel;
    private final Grant grant;
    private final long maxBytes;
    private final int timeoutMillis;

    public UploadTask(StorageService storage, SocketChannel channel, Grant grant,
                      long maxBytes, int timeoutMillis) {
        this.storage = storage;
        this.channel = channel;
        this.grant = grant;
        this.maxBytes = maxBytes;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void run() {
        Path part = null;
        try {
            channel.socket().setSoTimeout(timeoutMillis);

            if (grant == null || grant.op() != Op.PUT) {
                writeResponse("ERR BAD_TOKEN");
                return;
            }
            if (grant.size() < 0 || grant.size() > maxBytes) {
                writeResponse("ERR BAD_TOKEN");
                return;
            }

            Path target = storage.resolveName(grant.fileName());
            part = storage.resolvePartName(grant.fileName());
            storage.createStorageRoot();

            Files.deleteIfExists(part);
            try (FileChannel file = FileChannel.open(
                    part,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                long transferred = 0;
                while (transferred < grant.size()) {
                    long moved = file.transferFrom(channel, transferred, grant.size() - transferred);
                    if (moved <= 0) {
                        throw new IOException("Upload truncated: expected " + grant.size()
                                + " bytes but received " + transferred);
                    }
                    transferred += moved;
                }
            }

            try {
                Files.move(part, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                part = null;
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic move is required for upload completion", e);
            }

            writeResponse("OK");
            LOG.log(System.Logger.Level.INFO, "Upload complete: {0} ({1} bytes)",
                    grant.fileName(), grant.size());
        } catch (IOException | RuntimeException e) {
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException cleanupError) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not delete upload part file {0}: {1}",
                            part, cleanupError.getMessage());
                }
            }
            try {
                writeResponse("ERR BAD_REQUEST");
            } catch (IOException responseError) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not send upload error response: {0}",
                        responseError.getMessage());
            }
            LOG.log(System.Logger.Level.WARNING, "Upload failed: {0}", e.getMessage());
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not close upload socket: {0}", e.getMessage());
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
